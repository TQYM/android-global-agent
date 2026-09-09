package com.dsh.agentlite;

import java.util.List;

/** 语义节点模型 + prompt 渲染（移植自 agentd-go 的 semantics 包）。 */
public class NodeInfo {
    public int index;
    public String text = "", desc = "", id = "", cls = "";
    public boolean clickable, scrollable;
    public int cx, cy;

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
        b.append('[').append(ni.index).append("] ")
         .append(ni.label()).append(" @")
         .append(ni.cx).append(',').append(ni.cy).append(' ')
         .append(ni.id).append('\n');
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
}
