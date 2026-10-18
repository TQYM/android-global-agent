// SPDX-License-Identifier: Apache-2.0
package com.dsh.agentlite;

/**
 * T04a done 证据分级真值表：对从 AgentEngine.java 抽取出的**真实** judgeDone
 * 做行为回归（该方法为纯静态、无 android 依赖，故可桌面直测）。
 *
 * 屏幕变化只证明「可能发生过动作」，不证明目标达成——本测试锁定三档语义，
 * 防止后续有人把 UNPROVEN 重新合并进「完成」。
 */
public final class DoneEvidenceTest {
    private static int passed, failed;

    private static void check(String name, int want, int got) {
        if (want == got) {
            passed++;
            System.out.println("  PASS " + name + " → " + label(want));
        } else {
            failed++;
            System.out.println("  FAIL " + name + "：期望 " + label(want) + "，实得 " + label(got));
        }
    }

    private static String label(int v) {
        if (v == DoneJudgeHarness.DONE_PROVEN) return "PROVEN";
        if (v == DoneJudgeHarness.DONE_WEAK) return "WEAK";
        return "UNPROVEN";
    }

    private static void table(String label, boolean saw, String expected, String active, int want) {
        check(label, want, DoneJudgeHarness.judgeDone(saw, expected, active));
    }

    public static void main(String[] args) {
        System.out.println("== T04a done 证据分级真值表 ==");
        int p = DoneJudgeHarness.DONE_PROVEN;
        int w = DoneJudgeHarness.DONE_WEAK;
        int u = DoneJudgeHarness.DONE_UNPROVEN;

        if (p == w || w == u || p == u) {
            failed++;
            System.out.println("  FAIL 三档常量必须互不相同");
        } else {
            passed++;
            System.out.println("  PASS 三档常量互不相同");
        }

        // 无期望目标：只有前台变化才是弱证据
        table("无期望·无前台变化·无前台包", false, null, null, u);
        table("无期望·无前台变化·前台=A", false, null, "com.a", u);
        table("无期望·有前台变化·无前台包", true, null, null, w);
        table("无期望·有前台变化·前台=A", true, null, "com.a", w);

        // 期望目标命中 = 强证据（与是否有前台变化无关：核对本身就是证据）
        table("期望A·前台A·无变化", false, "com.a", "com.a", p);
        table("期望A·前台A·有变化", true, "com.a", "com.a", p);

        // 期望未命中：不得冒充 PROVEN
        table("期望A·前台B·无变化", false, "com.a", "com.b", u);
        table("期望A·前台B·有变化", true, "com.a", "com.b", w);
        table("期望A·前台未知·无变化", false, "com.a", null, u);
        table("期望A·前台未知·有变化", true, "com.a", null, w);
        table("期望A·前台空串·无变化", false, "com.a", "", u);
        table("期望A·前台空串·有变化", true, "com.a", "", w);

        // 空串期望（setting 动作的「离开本应用」语义）不构成强证据
        table("空期望·前台A·无变化", false, "", "com.a", u);
        table("空期望·前台A·有变化", true, "", "com.a", w);
        table("空期望·无前台包·有变化", true, "", null, w);

        // 相似但不同包名不得被当作命中（equals 语义，不做子串猜测）
        table("期望A·前台A.B·有变化", true, "com.a", "com.a.b", w);
        table("期望A·前台A.B·无变化", false, "com.a", "com.a.b", u);

        System.out.println();
        System.out.println(failed == 0
                ? "结果：" + passed + " 通过 / 0 失败"
                : "结果：" + passed + " 通过 / " + failed + " 失败");
        if (failed > 0) System.exit(1);
    }
}
