// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import java.util.List;
import java.util.Locale;

/**
 * 突发弹窗守卫（引擎侧）——SCHEMA 守则 4 在**记忆库复现路径**上的落地。
 *
 * <p>模型驱动路径靠提示词让模型「先处置弹窗再继续原任务」；而记忆库复现刻意零模型往返，
 * 必须由引擎自己识别可安全关闭的噪音层。判定宁可漏掉也不猜：误关一次任务对话框的代价
 * 远高于回退模型的一次往返。
 *
 * <p>与 {@link SensitiveGuard} 的分工：敏感守卫是硬边界，命中后必须交还用户；本类只处理
 * 明确的退出/稍后按钮。两者都可能命中时，调用方必须先走 SensitiveGuard。
 */
public final class PopupGuard {

    private PopupGuard() { }

    /** 强信号：完整匹配后，语义只表示退出当前覆盖层。 */
    static final String[] STRONG = {
            "关闭", "关闭广告", "跳过", "跳过广告", "跳过引导", "跳过教程",
            "close", "close ad", "skip", "skip ad", "dismiss", "dismiss ad",
            "残忍拒绝",
    };

    /** 弱信号：可能是任务动作，只有任务本身不要求取消/关闭时才允许。 */
    static final String[] WEAK = {
            "取消", "暂不", "暂不开启", "暂不更新", "暂不升级", "暂不登录",
            "稍后再说", "以后再说", "下次再说", "不用了", "不需要", "不，谢谢", "暂不评价",
            "later", "not now", "no thanks",
    };

    /** 单字符关闭图标必须同时满足位置与可点击性，不能只凭一个“x”猜。 */
    private static boolean isCloseGlyph(String t) {
        return "×".equals(t) || "✕".equals(t) || "x".equals(t);
    }

    /**
     * 当前屏幕上最像「突发弹窗关闭按钮」的节点；找不到返回 -1。
     *
     * <p>强信号优先；弱信号要求任务语义不冲突。所有候选必须可点击；单字符关闭图标还必须
     * 位于屏幕上半部的两侧（常见弹窗/广告关闭位），避免命中搜索框清空或列表删除图标。
     */
    public static int findDismissable(List<NodeInfo> nodes, String task, int screenW, int screenH) {
        if (nodes == null || nodes.isEmpty()) return -1;
        int weak = -1;
        boolean allowWeak = !taskNeedsDismissAction(task);
        for (int i = 0; i < nodes.size(); i++) {
            NodeInfo n = nodes.get(i);
            if (n == null || !n.clickable) continue;
            String t = FlowPlan.normText(n.label());
            if (t.isEmpty()) continue;
            if (isCloseGlyph(t)) {
                boolean sizeKnown = screenW > 0 && screenH > 0;
                boolean nearSide = !sizeKnown || n.cx <= screenW * 0.25 || n.cx >= screenW * 0.75;
                boolean upper = !sizeKnown || n.cy <= screenH * 0.65;
                if (nearSide && upper) return i;
                continue;
            }
            if (containsExact(STRONG, t)) return i;
            if (allowWeak && weak < 0 && containsExact(WEAK, t)) weak = i;
        }
        return weak;
    }

    /** 兼容纯逻辑调用；生产路径应传任务与屏幕尺寸。 */
    public static int findDismissable(List<NodeInfo> nodes) {
        return findDismissable(nodes, "", 0, 0);
    }

    /**
     * 记忆库里的一步是否是一次性弹窗关闭步骤。
     *
     * <p>只有强信号可以无条件视为瞬态；弱信号必须经过任务语义闸门。例如任务就是“取消订单”时，
     * 绝不能把「取消」当作运营弹窗步骤静默跳过。
     */
    public static boolean isDismissAnchor(FlowPlan.Step s, String task) {
        if (s == null) return false;
        String t = FlowPlan.normText(s.anchor);
        if (t.isEmpty()) return false;
        if (containsExact(STRONG, t) || isCloseGlyph(t)) return true;
        return !taskNeedsDismissAction(task) && containsExact(WEAK, t);
    }

    public static boolean isDismissAnchor(FlowPlan.Step s) {
        return isDismissAnchor(s, "");
    }

    /** 任务本身明确要求关闭/取消/退出时，弱词是目标动作，不是环境噪音。 */
    static boolean taskNeedsDismissAction(String task) {
        String t = task == null ? "" : FlowPlan.normText(task).toLowerCase(Locale.US);
        return t.contains("取消") || t.contains("关闭") || t.contains("关掉")
                || t.contains("退出") || t.contains("跳过") || t.contains("拒绝")
                || t.contains("cancel") || t.contains("close") || t.contains("dismiss")
                || t.contains("skip") || t.contains("exit");
    }

    private static boolean containsExact(String[] words, String t) {
        for (String w : words) {
            if (t.equals(FlowPlan.normText(w))) return true;
        }
        return false;
    }
}
