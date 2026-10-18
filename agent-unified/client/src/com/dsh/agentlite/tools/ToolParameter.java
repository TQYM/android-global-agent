// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** M3-01 工具参数的最小声明：名称、类型、必填性、范围和枚举。 */
public final class ToolParameter {
    public static final String STRING = "string";
    public static final String INTEGER = "integer";
    public static final String NUMBER = "number";
    public static final String BOOLEAN = "boolean";

    public final String name;
    public final String type;
    public final boolean required;
    public final Double minimum;
    public final Double maximum;
    public final List<String> enumValues;

    private ToolParameter(String name, String type, boolean required, Double minimum,
                          Double maximum, List<String> enumValues) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("参数名不能为空");
        if (!STRING.equals(type) && !INTEGER.equals(type) && !NUMBER.equals(type)
                && !BOOLEAN.equals(type)) {
            throw new IllegalArgumentException("未知参数类型: " + type);
        }
        if (minimum != null && maximum != null && minimum > maximum) {
            throw new IllegalArgumentException("参数范围无效: " + name);
        }
        this.name = name;
        this.type = type;
        this.required = required;
        this.minimum = minimum;
        this.maximum = maximum;
        this.enumValues = Collections.unmodifiableList(new ArrayList<>(
                enumValues == null ? Collections.<String>emptyList() : enumValues));
    }

    public static ToolParameter string(String name, boolean required) {
        return new ToolParameter(name, STRING, required, null, null, null);
    }

    public static ToolParameter integer(String name, boolean required, Integer minimum, Integer maximum) {
        return new ToolParameter(name, INTEGER, required,
                minimum == null ? null : minimum.doubleValue(),
                maximum == null ? null : maximum.doubleValue(), null);
    }

    public static ToolParameter number(String name, boolean required, Double minimum, Double maximum) {
        return new ToolParameter(name, NUMBER, required, minimum, maximum, null);
    }

    public static ToolParameter bool(String name, boolean required) {
        return new ToolParameter(name, BOOLEAN, required, null, null, null);
    }

    public static ToolParameter enumOf(String name, boolean required, String... values) {
        List<String> list = new ArrayList<>();
        if (values != null) Collections.addAll(list, values);
        if (list.isEmpty()) throw new IllegalArgumentException("枚举参数不能为空: " + name);
        return new ToolParameter(name, STRING, required, null, null, list);
    }

    public String toJson() {
        StringBuilder out = new StringBuilder("{\"type\":").append(quote(type));
        if (!enumValues.isEmpty()) {
            out.append(",\"enum\":[");
            for (int i = 0; i < enumValues.size(); i++) {
                if (i > 0) out.append(',');
                out.append(quote(enumValues.get(i)));
            }
            out.append(']');
        }
        if (minimum != null) out.append(",\"minimum\":").append(number(minimum));
        if (maximum != null) out.append(",\"maximum\":").append(number(maximum));
        return out.append('}').toString();
    }

    private static String number(Double value) {
        if (value.doubleValue() == value.intValue()) return String.valueOf(value.intValue());
        return String.valueOf(value);
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
