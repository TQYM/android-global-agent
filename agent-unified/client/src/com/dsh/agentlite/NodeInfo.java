// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import java.util.List;

/** 语义节点模型 + prompt 渲染（移植自 agentd-go 的 semantics 包）。 */
public class NodeInfo {
    public int index;
    public String text = "", desc = "", id = "", cls = "";
    public boolean clickable, scrollable;
    public int cx, cy;
    /** 密码/验证码框：采集时已置空文本，这里再记一次，供提示词出口做兜底（P0-2）。 */
    public boolean secret;

    /** 密码框在提示词里的占位符（保留节点、不泄露内容）。 */
    public static final String SECRET_MASK = "····（密码框，内容不采集）";

    public String label() {
        String l = (text + " " + desc).trim();
        if (l.isEmpty()) l = id;
        if (l.isEmpty()) l = cls;
        return l;
    }

    /**
     * 渲染为紧凑编号行：模型按 index 引用节点。
     * 节点过多时优先保留「可点击且靠屏幕顶部」的（搜索框/返回键等高频目标），
     * 再按原树序补齐，并附截断说明提示模型翻页获取其余节点。
     */
    public static String toPrompt(List<NodeInfo> nodes, int max) {
        StringBuilder b = new StringBuilder("当前屏幕可交互节点（编号 坐标=点击中心）：\n");
        if (max <= 0 || nodes.size() <= max) {
            for (NodeInfo ni : nodes) appendLine(b, ni);
            return b.toString();
        }
        // 第一轮：可点击节点按 y 坐标升序（顶部优先）
        List<NodeInfo> clickable = new java.util.ArrayList<>();
        for (NodeInfo ni : nodes) if (ni.clickable) clickable.add(ni);
        clickable.sort((x, y) -> x.cy - y.cy);
        java.util.HashSet<NodeInfo> used = new java.util.HashSet<>();
        int n = 0;
        for (NodeInfo ni : clickable) {
            if (n >= max) break;
            appendLine(b, ni); used.add(ni); n++;
        }
        // 第二轮：原树序补齐剩余名额
        for (NodeInfo ni : nodes) {
            if (n >= max) break;
            if (used.contains(ni)) continue;
            appendLine(b, ni); n++;
        }
        b.append("（共 ").append(nodes.size()).append(" 个节点，仅列出 ")
         .append(max).append(" 个高频目标；未找到目标请 scroll 翻页后重新感知）\n");
        return b.toString();
    }

    private static void appendLine(StringBuilder b, NodeInfo ni) {
        // 出网前统一过一遍脱敏（密码框标记 + 验证码形态兜底，P0-2）
        String label = ni.secret ? SECRET_MASK : sanitizeForPrompt(ni.label());
        b.append('[').append(ni.index).append("] ")
         .append(label).append(" @")
         .append(ni.cx).append(',').append(ni.cy).append(' ')
         .append(ni.id).append('\n');
    }

    /**
     * 出网前兜底：把疑似凭据形态的文本换成占位符（P0-2）。
     *
     * <p>采集层已经用 {@code isPassword()} 掩码，这里是**第二道**——防止将来新增
     * 采集路径（别的 Service / 纯视觉 OCR）绕过第一道。宁可让模型看不到验证码，
     * 也不能把短信验证码送到云端。
     */
    public static String sanitizeForPrompt(String s) {
        if (s == null || s.isEmpty()) return "";
        // 4~8 位纯数字（短信验证码）与显式密码框标记
        if (s.matches("^\\d{4,8}$")) return SECRET_MASK;
        return s;
    }

    /** 变化指纹：全节点加权哈希，任何可见内容变化都能被看门狗感知。 */
    public static String fingerprint(List<NodeInfo> nodes) {
        if (nodes == null || nodes.isEmpty()) return "empty";
        long h = nodes.size();
        for (int i = 0; i < nodes.size(); i++) {
            NodeInfo ni = nodes.get(i);
            h = h * 31 + (ni.text + "|" + ni.desc + "|" + ni.id).hashCode();
        }
        return String.valueOf(h);
    }

    /**
     * 仅有窄幅 SystemUI 窗口时，节点不代表应用页面，应切换到截图感知。
     * 保持纯 Java，供 Android 服务和桌面回归共同使用。
     */
    public static boolean isSystemChromeOnly(String pkg, int windowHeight,
                                              int screenHeight, int nodeCount) {
        if (nodeCount <= 0 || windowHeight <= 0) return false;
        if (pkg == null || !(pkg.startsWith("com.android.systemui")
                || pkg.startsWith("com.oplus.systemui") || "android".equals(pkg))) return false;
        int maxHeight = Math.max(180, screenHeight > 0 ? screenHeight / 10 : 180);
        return windowHeight <= maxHeight;
    }

    /** 由截图抽样像素生成轻量视觉指纹；顶部状态栏和底部手势区由调用方排除。 */
    public static String visualFingerprint(int width, int height, int[] argb) {
        if (argb == null || argb.length == 0) return "visual-empty";
        long h = 17L * Math.max(1, width) + height;
        for (int p : argb) {
            int r = ((p >> 16) & 0xff) >> 4;
            int g = ((p >> 8) & 0xff) >> 4;
            int b = (p & 0xff) >> 4;
            h = h * 131 + (r << 8) + (g << 4) + b;
        }
        return "visual-" + h;
    }
}
