// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite.tools;

import com.dsh.agentlite.ActionSchema;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M3-01 工具声明注册表。
 * 注册表拒绝重复名，查询返回不可变快照；不包含 Android 执行或策略判断。
 */
public final class ToolRegistry {
    private final Map<String, ToolSpec> tools = new LinkedHashMap<>();

    public void register(ToolSpec spec) {
        if (spec == null) throw new IllegalArgumentException("工具声明不能为空");
        if (tools.containsKey(spec.name)) throw new IllegalArgumentException("工具重复注册: " + spec.name);
        tools.put(spec.name, spec);
    }

    public void registerAll(Iterable<ToolSpec> specs) {
        if (specs == null) throw new IllegalArgumentException("工具声明集合不能为空");
        for (ToolSpec spec : specs) register(spec);
    }

    public ToolSpec get(String name) { return tools.get(name); }

    /** 按工具名执行 M3-02 参数校验；未知工具也必须在执行前返回结构化错误。 */
    public ValidationResult validate(String name, Map<String, String> arguments) {
        ToolSpec spec = get(name);
        if (spec == null) {
            return ValidationResult.invalid(ValidationResult.CODE_UNKNOWN_TOOL, "tool",
                    "工具未登记");
        }
        return spec.validate(arguments);
    }

    public boolean contains(String name) { return tools.containsKey(name); }

    public int size() { return tools.size(); }

    public Set<String> names() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(tools.keySet()));
    }

    public List<ToolSpec> all() {
        return Collections.unmodifiableList(new ArrayList<>(tools.values()));
    }

    /** 严格把当前 ActionSchema 的 25 个动作转换为声明，不改变旧执行路径。 */
    public static ToolRegistry builtIn() {
        ToolRegistry registry = new ToolRegistry();
        for (String name : ActionSchema.EXEC_ACTIONS) registry.register(specFor(name, false));
        for (String name : ActionSchema.CONTROL_ACTIONS) registry.register(specFor(name, true));
        return registry;
    }

    private static ToolSpec specFor(String name, boolean control) {
        List<ToolParameter> p = new ArrayList<>();
        boolean changesScreen = !"ask".equals(name) && !"done".equals(name) && !"fail".equals(name);
        boolean targetPackage = "app".equals(name) || "open_api".equals(name);
        String risk = "text".equals(name) || "remote".equals(name) || "open_api".equals(name)
                ? ToolSpec.RISK_SENSITIVE : ToolSpec.RISK_NORMAL;
        boolean vision = "tap".equals(name) || "longpress".equals(name) || "swipe".equals(name)
                || "scroll".equals(name) || "key".equals(name) || "edge_back".equals(name)
                || "back".equals(name) || "home".equals(name) || "wait".equals(name);
        String description = "控制动作 " + name;
        switch (name) {
            case "open_api":
                p.add(ToolParameter.string("target", true));
                p.add(ToolParameter.enumOf("intent", false, "search", "navigate", "play", "dial", "sms", "browse"));
                p.add(ToolParameter.string("param", true));
                break;
            case "open_url": p.add(ToolParameter.string("url", true)); break;
            case "setting": p.add(ToolParameter.string("page", true)); break;
            case "app": p.add(ToolParameter.string("package", true)); break;
            case "remote":
                p.add(ToolParameter.string("device", true));
                p.add(ToolParameter.string("task", true));
                break;
            case "brightness": p.add(ToolParameter.integer("level", false, 0, 255)); break;
            case "volume": p.add(ToolParameter.enumOf("dir", false, "up", "down", "mute")); break;
            case "statusbar": p.add(ToolParameter.enumOf("mode", false, "notifications", "settings")); break;
            case "tap":
                p.add(ToolParameter.integer("index", false, 0, null));
                p.add(ToolParameter.number("px", false, 0.0, 1.0));
                p.add(ToolParameter.number("py", false, 0.0, 1.0));
                p.add(ToolParameter.integer("x", false, 0, null));
                p.add(ToolParameter.integer("y", false, 0, null));
                break;
            case "longpress":
                p.add(ToolParameter.integer("index", false, 0, null));
                p.add(ToolParameter.number("px", false, 0.0, 1.0));
                p.add(ToolParameter.number("py", false, 0.0, 1.0));
                p.add(ToolParameter.integer("x", false, 0, null));
                p.add(ToolParameter.integer("y", false, 0, null));
                p.add(ToolParameter.integer("dur", false, 1, 8000));
                break;
            case "swipe":
                p.add(ToolParameter.integer("x1", false, 0, null));
                p.add(ToolParameter.integer("y1", false, 0, null));
                p.add(ToolParameter.integer("x2", false, 0, null));
                p.add(ToolParameter.integer("y2", false, 0, null));
                p.add(ToolParameter.number("px1", false, 0.0, 1.0));
                p.add(ToolParameter.number("py1", false, 0.0, 1.0));
                p.add(ToolParameter.number("px2", false, 0.0, 1.0));
                p.add(ToolParameter.number("py2", false, 0.0, 1.0));
                p.add(ToolParameter.integer("dur", false, 1, 8000));
                break;
            case "scroll": p.add(ToolParameter.enumOf("direction", true, "up", "down", "left", "right")); break;
            case "key": p.add(ToolParameter.integer("code", true, 0, 1000)); break;
            case "edge_back": p.add(ToolParameter.enumOf("side", true, "left", "right")); break;
            case "text":
                p.add(ToolParameter.string("text", true));
                p.add(ToolParameter.bool("append", false));
                break;
            case "wait": p.add(ToolParameter.integer("ms", false, 200, 8000)); break;
            case "ask": p.add(ToolParameter.string("question", true)); break;
            case "done": p.add(ToolParameter.string("summary", true)); break;
            case "fail": p.add(ToolParameter.string("reason", true)); break;
            default:
                // 无参数工具：wifi/bluetooth/list_apps/back/home 等。
        }
        return new ToolSpec(name, description, p, changesScreen, targetPackage, risk, vision, control);
    }
}
