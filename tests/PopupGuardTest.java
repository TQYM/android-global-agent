// SPDX-License-Identifier: Apache-2.0
// 桌面 JVM 回归：复现路径的突发弹窗识别（零 Android 依赖）。

import com.dsh.agentlite.FlowPlan;
import com.dsh.agentlite.NodeInfo;
import com.dsh.agentlite.PopupGuard;

import java.util.ArrayList;
import java.util.List;

public final class PopupGuardTest {
    private static int pass, fail;

    public static void main(String[] args) {
        strongSignals();
        unsafeLookalikes();
        taskSemantics();
        closeGlyphGeometry();
        priority();
        System.out.println("\n通过 " + pass + " / 失败 " + fail);
        if (fail > 0) System.exit(1);
    }

    private static void strongSignals() {
        section("强关闭信号：完整匹配才命中");
        for (String text : new String[]{"关闭", "关闭广告", "跳过", "跳过引导", "Skip", "Dismiss ad", "残忍拒绝"}) {
            List<NodeInfo> ns = one(text, true, 950, 120);
            yes(text + " 可安全关闭", PopupGuard.findDismissable(ns, "打开设置", 1000, 2000) == 0);
        }
        yes("空节点不命中", PopupGuard.findDismissable(new ArrayList<>(), "", 1000, 2000) == -1);
        yes("null 节点不命中", PopupGuard.findDismissable(null, "", 1000, 2000) == -1);
    }

    private static void unsafeLookalikes() {
        section("相似但危险的任务按钮绝不命中");
        for (String text : new String[]{"取消订单", "关闭交易", "立即领取", "立即更新", "知道了", "去开启", "确定"}) {
            yes(text + " 不命中", PopupGuard.findDismissable(one(text, true, 950, 120), "打开设置", 1000, 2000) == -1);
        }
        yes("不可点击的关闭文本不命中",
                PopupGuard.findDismissable(one("关闭", false, 950, 120), "", 1000, 2000) == -1);
    }

    private static void taskSemantics() {
        section("弱信号受任务语义约束");
        yes("普通任务可点运营弹窗的取消",
                PopupGuard.findDismissable(one("取消", true, 900, 300), "打开设置", 1000, 2000) == 0);
        yes("取消订单任务不能把取消当噪音",
                PopupGuard.findDismissable(one("取消", true, 900, 300), "取消订单", 1000, 2000) == -1);
        FlowPlan.Step cancel = new FlowPlan.Step();
        cancel.act = "tap"; cancel.anchor = "取消";
        yes("旧记录：普通任务的取消步可兼容跳过", PopupGuard.isDismissAnchor(cancel, "打开设置"));
        yes("旧记录：取消任务的取消步不可跳过", !PopupGuard.isDismissAnchor(cancel, "取消订阅"));
        FlowPlan.Step close = new FlowPlan.Step();
        close.act = "tap"; close.anchor = "关闭广告";
        yes("强关闭步可识别为瞬态", PopupGuard.isDismissAnchor(close, "打开设置"));
        yes("空锚点不是瞬态", !PopupGuard.isDismissAnchor(new FlowPlan.Step(), ""));
    }

    private static void closeGlyphGeometry() {
        section("单字符关闭图标必须在安全位置");
        yes("右上角可点击 × 命中",
                PopupGuard.findDismissable(one("×", true, 950, 100), "", 1000, 2000) == 0);
        yes("屏幕中央 x 不命中（可能是清空/删除）",
                PopupGuard.findDismissable(one("x", true, 500, 1000), "", 1000, 2000) == -1);
        yes("底部角落 x 不命中",
                PopupGuard.findDismissable(one("x", true, 950, 1900), "", 1000, 2000) == -1);
    }

    private static void priority() {
        section("强信号优先于弱信号");
        List<NodeInfo> ns = new ArrayList<>();
        ns.add(node("取消", true, 100, 300));
        ns.add(node("关闭", true, 900, 300));
        eq("强信号排在树后仍优先", 1, PopupGuard.findDismissable(ns, "打开设置", 1000, 2000));
    }

    private static List<NodeInfo> one(String text, boolean clickable, int cx, int cy) {
        List<NodeInfo> out = new ArrayList<>();
        out.add(node(text, clickable, cx, cy));
        return out;
    }

    private static NodeInfo node(String text, boolean clickable, int cx, int cy) {
        NodeInfo n = new NodeInfo();
        n.index = 0; n.text = text; n.clickable = clickable; n.cx = cx; n.cy = cy;
        return n;
    }

    private static void section(String title) { System.out.println("\n== " + title + " =="); }
    private static void yes(String label, boolean ok) { report(ok, label); }
    private static void eq(String label, int expect, int actual) { report(expect == actual, label + "（期望=" + expect + " 实际=" + actual + "）"); }
    private static void report(boolean ok, String label) {
        if (ok) { pass++; System.out.println("  ✓ " + label); }
        else { fail++; System.out.println("  ✗ " + label); }
    }
    private PopupGuardTest() { }
}
