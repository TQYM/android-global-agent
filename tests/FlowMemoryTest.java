// SPDX-License-Identifier: Apache-2.0
//
// 桌面 JVM 回归：操作记忆库的纯逻辑层（零 android / org.json 依赖，可脱离设备直跑）。
//   javac --release 11 -encoding UTF-8 -d "$TMP" tests/FlowMemoryTest.java \
//     agent-lite/src/com/dsh/agentlite/{FlowPlan,NodeInfo,TextNormalizer,FuzzyFlow}.java
//   java -cp "$TMP" FlowMemoryTest
//
// 为什么值得单独测：记忆库要替用户**直接动手**（不再问模型），判错的代价不对称——
//   · 把「点确认」锚到「点取消」：用户看到任务完成，实际做了反事；
//   · 把验证码/密码写进本地文件：一次泄漏，永久损失；
//   · 把一条已经失效的路径反复照做：用户被卡在同一条死路上，还不知道为什么。
// 而「少命中一次、少记一条」的代价只是多花一次模型往返。所以每条模糊地带都必须偏向保守，
// 并用断言把这份保守钉死在代码里。

import com.dsh.agentlite.FlowPlan;
import com.dsh.agentlite.NodeInfo;

import java.util.ArrayList;
import java.util.List;

public final class FlowMemoryTest {

    private static final long NOW = 1789000000000L;      // 固定"现在"，避免测试随时间漂移

    private static int pass = 0, fail = 0;

    public static void main(String[] args) {
        fingerprints();
        admission();
        credentials();
        anchoring();
        freshness();
        eviction();
        matching();
        visualPerception();

        System.out.println();
        System.out.println("通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    // ---------- 1. 指纹 ----------

    private static void fingerprints() {
        section("指纹：同一句话必得同一指纹，不同话必不相同");

        String a = FlowPlan.normalize("帮我打开微信给张三发消息");
        String b = FlowPlan.normalize("打开微信给张三发消息");
        eq("归一化剥离填充词", "打开微信给张三发消息", a);
        eq("同一句话（含填充词差异）指纹相同", FlowPlan.exactFp(a), FlowPlan.exactFp(b));

        String c = FlowPlan.normalize("打开支付宝给张三发消息");
        yes("不同目标指纹不同", !FlowPlan.exactFp(a).equals(FlowPlan.exactFp(c)));

        // 骨架：槽位值不同但结构相同 → 同款流程
        String skelA = "打开<app>给<联系人>发消息";
        String skelB = "打开<app>给<联系人>发消息";
        eq("同骨架指纹相同", FlowPlan.skeletonFp("sms", skelA), FlowPlan.skeletonFp("sms", skelB));
        yes("骨架相同但意图不同 → 指纹不同",
                !FlowPlan.skeletonFp("sms", skelA).equals(FlowPlan.skeletonFp("general", skelA)));
        eq("指纹是 40 位十六进制", 40, FlowPlan.exactFp(a).length());
    }

    // ---------- 2. 入库把关 ----------

    private static void admission() {
        section("入库把关：白名单外的动作整条作废（半条流程比没有流程更危险）");

        List<FlowPlan.Step> ok = new ArrayList<>();
        ok.add(step("app"));
        ok.add(step("tap"));
        eq("纯界面动作可入库", "", FlowPlan.rejectReason(ok));
        yes("isReplayable 一致", FlowPlan.isReplayable(ok));

        List<FlowPlan.Step> bad = new ArrayList<>(ok);
        bad.add(step("remote"));
        yes("含 remote → 拒绝（跨设备副作用不可照做）",
                FlowPlan.rejectReason(bad).contains("remote"));

        List<FlowPlan.Step> api = new ArrayList<>();
        api.add(step("open_api"));
        yes("含 open_api → 拒绝（支付/短信参数与当次语境绑定）",
                FlowPlan.rejectReason(api).contains("open_api"));

        List<FlowPlan.Step> url = new ArrayList<>(ok);
        url.add(step("open_url"));
        yes("含 open_url → 拒绝（R-5：外部链接不得零确认回放）",
                FlowPlan.rejectReason(url).contains("open_url"));

        List<FlowPlan.Step> ask = new ArrayList<>();
        ask.add(step("ask"));
        yes("含 ask → 拒绝（复现不该再问用户一遍）",
                FlowPlan.rejectReason(ask).contains("ask"));

        yes("空步骤 → 拒绝", !FlowPlan.rejectReason(new ArrayList<>()).isEmpty());

        List<FlowPlan.Step> tooLong = new ArrayList<>();
        for (int i = 0; i <= FlowPlan.MAX_STEPS; i++) tooLong.add(step("tap"));
        yes("步骤超上限 → 拒绝", FlowPlan.rejectReason(tooLong).contains("步骤过多"));
    }

    // ---------- 3. 凭据红线 ----------

    private static void credentials() {
        section("凭据红线：验证码/密码形态绝不进本地文件");

        yes("4 位验证码", FlowPlan.hasCredential("8842"));
        yes("带分隔符的卡号", FlowPlan.hasCredential("6222 0000 1234"));
        yes("含「密码」二字", FlowPlan.hasCredential("我的支付密码"));
        yes("含「验证码」", FlowPlan.hasCredential("验证码 5124"));
        no("普通口语不算凭据", FlowPlan.hasCredential("奶茶三分糖"));
        no("短数字不算（如「2 杯」）", FlowPlan.hasCredential("2"));
        no("空串不算", FlowPlan.hasCredential(""));

        List<FlowPlan.Step> steps = new ArrayList<>();
        FlowPlan.Step t = new FlowPlan.Step();
        t.act = "text";
        t.text = "123456";
        steps.add(t);
        yes("输入验证码的流程整条不入库", !FlowPlan.isReplayable(steps));
    }

    // ---------- 4. 锚点定位（复现的核心） ----------

    private static void anchoring() {
        section("锚点定位：rid → 文本相等 → 文本包含；多命中不猜");

        List<NodeInfo> nodes = new ArrayList<>();
        nodes.add(node(3, "搜索", "com.x:id/search", "android.widget.EditText", 500, 100));
        nodes.add(node(4, "取消", "com.x:id/cancel", "android.widget.Button", 100, 900));
        nodes.add(node(5, "确认", "com.x:id/ok", "android.widget.Button", 800, 900));

        // 文本相等
        FlowPlan.Step s = tapStep("确认", "", "");
        eq("文本相等命中", 5, nodes.get(FlowPlan.matchAnchor(s, nodes, 1000, 2000)).index);

        // rid 优先：屏幕上有两个同名节点，只有 rid 能精确区分（纯文本会因多命中而拒判）
        List<NodeInfo> twins = new ArrayList<>();
        twins.add(node(7, "更多", "com.x:id/more_a", "android.widget.ImageView", 100, 100));
        twins.add(node(8, "更多", "com.x:id/more_b", "android.widget.ImageView", 900, 1800));
        FlowPlan.Step ridPick = tapStep("更多", "com.x:id/more_b", "");
        eq("同文本多节点时由 rid 精确定位", 8,
                twins.get(FlowPlan.matchAnchor(ridPick, twins, 1000, 2000)).index);

        // rid 命中但文本明显不同（列表项复用 id）→ 不算命中
        FlowPlan.Step ridMismatch = tapStep("这是一个完全不同的条目", "com.x:id/ok", "");
        eq("rid 相同但文本明显不同 → 不命中",
                -1, FlowPlan.matchAnchor(ridMismatch, nodes, 1000, 2000));

        // 文本包含：锚点与节点文本都必须 ≥2 字，否则不认
        FlowPlan.Step partial = tapStep("搜", "", "");
        eq("单字锚点不认（「搜」能同时命中「搜索」「搜集」）",
                -1, FlowPlan.matchAnchor(partial, nodes, 1000, 2000));
        FlowPlan.Step partial2 = tapStep("搜索栏", "", "");
        eq("包含命中", 3, nodes.get(FlowPlan.matchAnchor(partial2, nodes, 1000, 2000)).index);

        // 多命中：有坐标 → 选最近的；无坐标 → 不猜
        List<NodeInfo> dup = new ArrayList<>();
        dup.add(node(1, "更多", "", "android.widget.ImageView", 100, 100));
        dup.add(node(2, "更多", "", "android.widget.ImageView", 900, 1800));
        FlowPlan.Step noPoint = tapStep("更多", "", "");
        eq("多命中且无坐标 → 不猜", -1, FlowPlan.matchAnchor(noPoint, dup, 1000, 2000));

        FlowPlan.Step withPoint = tapStep("更多", "", "");
        withPoint.px = 0.9; withPoint.py = 0.9;      // 归一化后接近第二个节点（900/1000, 1800/2000）
        eq("多命中有坐标 → 取最近的", 2,
                dup.get(FlowPlan.matchAnchor(withPoint, dup, 1000, 2000)).index);

        // 无锚点无 rid → 交坐标兜底（返回 -1 是正常结果）
        FlowPlan.Step bare = tapStep("", "", "android.widget.FrameLayout");
        eq("只有类名且屏幕上不唯一 → 不认", -1,
                FlowPlan.matchAnchor(bare, nodes, 1000, 2000));

        yes("空节点表 → -1", FlowPlan.matchAnchor(s, new ArrayList<>(), 1000, 2000) == -1);
    }

    // ---------- 5. 新鲜度与退役 ----------

    private static void freshness() {
        section("新鲜度：只有「近期成功过」的记录才允许直接照做");

        FlowPlan.Record r = record("打开微信", NOW);
        r.oks = 1; r.lastUsed = NOW;
        yes("刚成功 → 可命中", FlowPlan.isFresh(r, NOW + 1000));
        yes("23 小时内 → 可命中", FlowPlan.isFresh(r, NOW + 23 * 3600_000L));
        yes("超过 24 小时 → 不再命中", !FlowPlan.isFresh(r, NOW + 25 * 3600_000L));

        FlowPlan.Record never = record("打开支付宝", NOW);
        never.oks = 0; never.lastUsed = NOW;
        yes("从未成功过 → 不命中", !FlowPlan.isFresh(never, NOW + 1000));

        FlowPlan.Record bad = record("打开淘宝", NOW);
        bad.oks = 3; bad.fails = FlowPlan.MAX_FAILS; bad.lastUsed = NOW;
        yes("连续失败到阈值 → 退役，不再命中", !FlowPlan.isFresh(bad, NOW + 1000));
        yes("shouldRetire 一致", FlowPlan.shouldRetire(bad));

        bad.oks = 4; bad.fails = 0;      // 又一次成功 → 复活
        yes("再成功一次即复活（不永久污名化）", FlowPlan.isFresh(bad, NOW + 1000));
        yes("空步骤记录不命中", !FlowPlan.isFresh(new FlowPlan.Record(), NOW));
    }

    // ---------- 6. 容量淘汰 ----------

    private static void eviction() {
        section("容量淘汰：先扔退役的，再扔从未成功的，最后才按最久未用");

        List<FlowPlan.Record> all = new ArrayList<>();
        for (int i = 0; i < FlowPlan.MAX_RECORDS; i++) {
            FlowPlan.Record r = record("任务" + i, NOW);
            r.oks = 1; r.lastUsed = NOW + i;      // 越靠后越新
            all.add(r);
        }
        // 一条刚失败过的退役记录（lastUsed 最新，纯 LRU 会把它留下）
        FlowPlan.Record retired = record("已失效路径", NOW);
        retired.oks = 0; retired.fails = FlowPlan.MAX_FAILS; retired.lastUsed = NOW + 999999;
        all.add(retired);

        int removed = FlowPlan.evict(all);
        eq("淘汰到容量上限", FlowPlan.MAX_RECORDS, all.size());
        eq("超出 1 条 → 淘汰 1 条", 1, removed);
        boolean stillThere = false;
        for (FlowPlan.Record r : all) if ("已失效路径".equals(r.task)) stillThere = true;
        yes("退役记录先被淘汰（而不是按 LRU 留下它）", !stillThere);
    }

    // ---------- 7. 命中（精确 + 骨架） ----------

    private static void matching() {
        section("命中：同一句话 → 精确命中；同款不同参 → 骨架命中");

        List<FlowPlan.Record> all = new ArrayList<>();
        FlowPlan.Record r = record("打开微信给张三发消息说我到了", NOW);
        r.normalized = FlowPlan.normalize(r.task);
        r.fp = FlowPlan.exactFp(r.normalized);
        r.fpSkel = FlowPlan.skeletonFp("sms", "打开<app>给<联系人>发消息说<内容>");
        r.steps.add(step("app"));
        r.oks = 1; r.lastUsed = NOW;
        all.add(r);

        yes("同一句话（带填充词）→ 精确命中",
                FlowPlan.matchExact(all, FlowPlan.normalize("麻烦帮我打开微信给张三发消息说我到了"), NOW + 1000) != null);
        yes("无关任务 → 不命中",
                FlowPlan.matchExact(all, FlowPlan.normalize("打开计算器"), NOW + 1000) == null);
        yes("骨架命中（换联系人/换内容同理）",
                FlowPlan.matchSkeleton(all, "sms", "打开<app>给<联系人>发消息说<内容>", NOW + 1000) != null);
        yes("骨架为空 → 不命中（不做模糊猜测）",
                FlowPlan.matchSkeleton(all, "sms", "", NOW + 1000) == null);

        // 退役记录不得被命中
        r.fails = FlowPlan.MAX_FAILS;
        yes("退役后精确命中失效",
                FlowPlan.matchExact(all, r.normalized, NOW + 1000) == null);

        // 文本归一化
        eq("全角标点与空格被归一", "确认", FlowPlan.normText(" 确 认。 "));
        eq("大小写归一", "ok", FlowPlan.normText("OK"));
    }

    // ---------- 辅助 ----------

    private static FlowPlan.Step step(String act) {
        FlowPlan.Step s = new FlowPlan.Step();
        s.act = act;
        s.px = 0.5; s.py = 0.5;
        return s;
    }

    private static void visualPerception() {
        section("感知降级：状态栏节点不冒充应用页面，截图摘要可区分真实变化");
        yes("SystemUI 窄窗口识别", NodeInfo.isSystemChromeOnly(
                "com.android.systemui", 135, 2640, 5));
        no("正常应用窗口不误判", NodeInfo.isSystemChromeOnly(
                "com.tencent.mm", 2640, 2640, 5));
        no("宽系统窗口不误判", NodeInfo.isSystemChromeOnly(
                "com.android.systemui", 2640, 2640, 5));
        no("空节点不触发状态栏判定", NodeInfo.isSystemChromeOnly(
                "com.android.systemui", 135, 2640, 0));
        int[] stable = {0xff101010, 0xff202020, 0xff303030};
        int[] changed = {0xff101010, 0xfff02020, 0xff303030};
        yes("相同像素摘要稳定", NodeInfo.visualFingerprint(3, 1, stable)
                .equals(NodeInfo.visualFingerprint(3, 1, stable)));
        yes("不同像素摘要可区分", !NodeInfo.visualFingerprint(3, 1, stable)
                .equals(NodeInfo.visualFingerprint(3, 1, changed)));
        yes("视觉摘要带 visual 前缀", NodeInfo.visualFingerprint(3, 1, stable).startsWith("visual-"));
    }

    private static FlowPlan.Step tapStep(String anchor, String rid, String cls) {
        FlowPlan.Step s = new FlowPlan.Step();
        s.act = "tap";
        s.anchor = anchor;
        s.rid = rid;
        s.cls = cls;
        return s;
    }

    private static NodeInfo node(int index, String text, String id, String cls, int cx, int cy) {
        NodeInfo n = new NodeInfo();
        n.index = index;
        n.text = text;
        n.id = id;
        n.cls = cls;
        n.cx = cx;
        n.cy = cy;
        return n;
    }

    private static FlowPlan.Record record(String task, long created) {
        FlowPlan.Record r = new FlowPlan.Record();
        r.task = task;
        r.created = created;
        r.lastUsed = created;
        r.steps.add(step("tap"));
        return r;
    }

    private static void section(String title) {
        System.out.println();
        System.out.println("== " + title + " ==");
    }

    private static void eq(String label, String expect, String actual) {
        boolean ok = (expect == null) ? (actual == null) : expect.equals(actual);
        report(ok, label + "（期望=" + expect + " 实际=" + actual + "）");
    }

    private static void eq(String label, int expect, int actual) {
        report(expect == actual, label + "（期望=" + expect + " 实际=" + actual + "）");
    }

    private static void yes(String label, boolean cond) {
        report(cond, label);
    }

    private static void no(String label, boolean cond) {
        report(!cond, label);
    }

    private static void report(boolean ok, String label) {
        if (ok) {
            pass++;
            System.out.println("  ✓ " + label);
        } else {
            fail++;
            System.out.println("  ✗ " + label);
        }
    }

    private FlowMemoryTest() { }
}
