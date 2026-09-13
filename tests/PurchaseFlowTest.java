// SPDX-License-Identifier: Apache-2.0
//
// 桌面 JVM 回归：PurchaseFlow 是纯逻辑（零 android / org.json 依赖），可脱离 Android 直跑。
//   javac -d /tmp/pftest tests/PurchaseFlowTest.java agent-lite/src/com/dsh/agentlite/PurchaseFlow.java
//   java  -cp /tmp/pftest PurchaseFlowTest
//
// 为什么值得单独测：这段逻辑决定「用户的钱到没到账」。
// 判错的两个方向代价不对等——假阳性会让流程去创建用不上的 Key（用户以为充值成功却不能用），
// 假阴性只是让用户手动点一次重新检查。所以每个模糊地带都必须偏向保守。

import com.dsh.agentlite.PurchaseFlow;
import com.dsh.agentlite.PurchaseFlow.Decision;
import com.dsh.agentlite.PurchaseFlow.KeyInfo;
import com.dsh.agentlite.PurchaseFlow.Snapshot;
import com.dsh.agentlite.PurchaseFlow.Verdict;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class PurchaseFlowTest {

    private static int pass = 0, fail = 0;

    private static final long NOW = 1_800_000_000_000L;   // 固定"当前时刻"，避免依赖真实时钟

    public static void main(String[] args) {
        // ---------- 1. 基线快照 ----------
        List<KeyInfo> base = Arrays.asList(
                new KeyInfo(101L, NOW - 86_400_000L, "旧 key", false),
                new KeyInfo(102L, NOW - 3_600_000L, "另一个旧 key", false));
        Snapshot before = new Snapshot(base, NOW);

        eq("快照只收非零 id", "2", String.valueOf(before.ids.size()));
        yes("快照含 101", before.ids.contains(101L));
        yes("快照不含不存在的 999", !before.ids.contains(999L));

        Snapshot withZero = new Snapshot(Arrays.asList(
                new KeyInfo(0L, NOW, "id 缺失", false),
                new KeyInfo(7L, NOW, "正常", false)), NOW);
        eq("id=0 不进快照（避免把所有 id 缺失的当成同一个）", "1", String.valueOf(withZero.ids.size()));

        // ---------- 2. 主路径：id 新增 → 判定成功 ----------
        List<KeyInfo> afterNew = new ArrayList<>(base);
        afterNew.add(new KeyInfo(103L, NOW + 5_000L, "AgentLite 自动创建", false));
        Decision d1 = PurchaseFlow.evaluate(before, afterNew);
        eq("新增 id → NEW_KEY_FOUND", Verdict.NEW_KEY_FOUND.name(), d1.verdict.name());
        yes("可继续流程", d1.canProceed());
        eq("取到的是新 key", "103", String.valueOf(d1.key.apiKeyId));

        // ---------- 3. 没有新增 → 判定未付款（关键：不能误判成功） ----------
        Decision d2 = PurchaseFlow.evaluate(before, base);
        eq("无新增 → NO_NEW_KEY", Verdict.NO_NEW_KEY.name(), d2.verdict.name());
        yes("不可继续", !d2.canProceed());

        // 用户"切出去看了眼微信再回来"：列表完全相同 —— 必须不判成功
        Decision d3 = PurchaseFlow.evaluate(before, new ArrayList<>(base));
        eq("列表无变化 → 不判成功（消除假阳性）", Verdict.NO_NEW_KEY.name(), d3.verdict.name());

        // ---------- 4. 连点两次购买 → 多个新增，取最新 ----------
        List<KeyInfo> afterTwo = new ArrayList<>(base);
        afterTwo.add(new KeyInfo(103L, NOW + 1_000L, "第一次", false));
        afterTwo.add(new KeyInfo(104L, NOW + 9_000L, "第二次", false));
        Decision d4 = PurchaseFlow.evaluate(before, afterTwo);
        eq("多个新增取 gmtCreate 最新", "104", String.valueOf(d4.key.apiKeyId));

        // ---------- 5. 快照缺失 → 必须拒绝推进 ----------
        Decision d5 = PurchaseFlow.evaluate(null, afterNew);
        eq("无基线 → NO_BASELINE", Verdict.NO_BASELINE.name(), d5.verdict.name());
        yes("无基线不可继续（禁止据此推进）", !d5.canProceed());

        // ---------- 6. 空列表 / null ----------
        eq("空列表 → NO_NEW_KEY", Verdict.NO_NEW_KEY.name(),
                PurchaseFlow.evaluate(before, Collections.emptyList()).verdict.name());
        eq("null 列表 → NO_NEW_KEY", Verdict.NO_NEW_KEY.name(),
                PurchaseFlow.evaluate(before, null).verdict.name());

        // ---------- 7. 兜底路径：id 全缺失时按 gmtCreate ----------
        Snapshot beforeNoIds = new Snapshot(
                Arrays.asList(new KeyInfo(0L, NOW - 86_400_000L, "旧", false)), NOW);
        List<KeyInfo> afterNoIds = Arrays.asList(
                new KeyInfo(0L, NOW - 86_400_000L, "旧", false),
                new KeyInfo(0L, NOW + 1_000L, "新但无 id", false));
        Decision d7 = PurchaseFlow.evaluate(beforeNoIds, afterNoIds);
        eq("id 缺失时按 gmtCreate 判定新增", Verdict.NEW_KEY_FOUND.name(), d7.verdict.name());

        // 兜底路径的宽限：刚好在快照时刻之前一点点（时钟偏差范围内）
        List<KeyInfo> afterGrace = Arrays.asList(
                new KeyInfo(0L, NOW - 30_000L, "快照前 30 秒创建", false));
        Decision d8 = PurchaseFlow.evaluate(beforeNoIds, afterGrace);
        eq("宽限内的 Key 判为新增（容忍时钟偏差）", Verdict.NEW_KEY_FOUND.name(), d8.verdict.name());

        // 远超宽限的旧 Key：不得判成新增
        List<KeyInfo> afterOld = Arrays.asList(
                new KeyInfo(0L, NOW - 10 * 60_000L, "十分钟前创建的旧 key", false));
        Decision d9 = PurchaseFlow.evaluate(beforeNoIds, afterOld);
        eq("远超宽限的旧 Key 不判新增", Verdict.NO_NEW_KEY.name(), d9.verdict.name());

        // ---------- 8. 混合：部分 id 缺失时，只要有一个可比对的 id 就走 id 路径 ----------
        List<KeyInfo> mixed = Arrays.asList(
                new KeyInfo(0L, NOW + 5_000L, "无 id 的新", false),
                new KeyInfo(102L, NOW - 3_600_000L, "已有的", false));
        Decision d10 = PurchaseFlow.evaluate(before, mixed);
        eq("有可比对 id 时不走时间兜底（无新增 id）", Verdict.NO_NEW_KEY.name(), d10.verdict.name());

        // ---------- 9. disabled 不影响"是否新增"的判定 ----------
        List<KeyInfo> afterDisabled = new ArrayList<>(base);
        afterDisabled.add(new KeyInfo(105L, NOW + 2_000L, "新建但已禁用", true));
        Decision d11 = PurchaseFlow.evaluate(before, afterDisabled);
        eq("新建但已禁用仍算新增（是否可用由后续校验决定）",
                Verdict.NEW_KEY_FOUND.name(), d11.verdict.name());

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    private static void eq(String name, String want, String got) {
        if (want.equals(got)) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name + "\n       期望: " + want + "\n       实际: " + got); }
    }

    private static void yes(String name, boolean cond) {
        if (cond) { pass++; System.out.println("  ok   " + name); }
        else { fail++; System.out.println("  FAIL " + name); }
    }
}
