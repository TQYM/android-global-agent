package com.dsh.agent;

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

    /** 渲染为紧凑编号行：模型按 index 引用节点。 */
    public static String toPrompt(List<NodeInfo> nodes, int max) {
        int n = (max <= 0 || nodes.size() <= max) ? nodes.size() : max;
        StringBuilder b = new StringBuilder("当前屏幕可交互节点（编号 坐标=点击中心）：\n");
        for (int i = 0; i < n; i++) {
            NodeInfo ni = nodes.get(i);
            b.append('[').append(ni.index).append("] ")
             .append(ni.label()).append(" @")
             .append(ni.cx).append(',').append(ni.cy).append(' ')
             .append(ni.id).append('\n');
        }
        return b.toString();
    }

    /** 变化指纹：用于动作后的自适应等待。 */
    public static String fingerprint(List<NodeInfo> nodes) {
        if (nodes == null || nodes.isEmpty()) return "empty";
        StringBuilder b = new StringBuilder(String.valueOf(nodes.size()));
        int[] probe = {0, nodes.size() / 2, nodes.size() - 1};
        for (int i : probe) {
            NodeInfo ni = nodes.get(i);
            b.append('|').append(ni.text).append(ni.desc).append(ni.id);
        }
        return b.toString();
    }
}
