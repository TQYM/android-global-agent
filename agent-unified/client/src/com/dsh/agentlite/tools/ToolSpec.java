// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite.tools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * M3-01 声明式工具能力描述。
 * 仅描述工具，不持有 Android 执行器；执行接线属于后续 M3-04/M3-07。
 */
public final class ToolSpec {
    public static final String RISK_NORMAL = "NORMAL";
    public static final String RISK_SENSITIVE = "SENSITIVE";
    public static final String RISK_HIGH = "HIGH";

    public final String name;
    public final String description;
    public final List<ToolParameter> parameters;
    public final boolean changesScreen;
    public final boolean requiresTargetPackage;
    public final String risk;
    public final boolean allowedInVisionMode;
    public final boolean controlAction;

    public ToolSpec(String name, String description, List<ToolParameter> parameters,
                    boolean changesScreen, boolean requiresTargetPackage, String risk,
                    boolean allowedInVisionMode, boolean controlAction) {
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("工具名不能为空");
        if (description == null || description.trim().isEmpty()) throw new IllegalArgumentException("工具描述不能为空");
        if (!RISK_NORMAL.equals(risk) && !RISK_SENSITIVE.equals(risk) && !RISK_HIGH.equals(risk)) {
            throw new IllegalArgumentException("未知工具风险: " + risk);
        }
        this.name = name;
        this.description = description;
        this.parameters = Collections.unmodifiableList(new ArrayList<>(
                parameters == null ? Collections.<ToolParameter>emptyList() : parameters));
        this.changesScreen = changesScreen;
        this.requiresTargetPackage = requiresTargetPackage;
        this.risk = risk;
        this.allowedInVisionMode = allowedInVisionMode;
        this.controlAction = controlAction;
    }

    /**
     * 按本声明校验调用参数。参数以字符串承载，适配当前 AgentEngine 的 JSONObject 读取边界。
     * 数字类型按严格十进制解析，禁止 NaN、Infinity 和带小数的 integer。
     */
    public ValidationResult validate(Map<String, String> arguments) {
        List<ValidationResult.Error> errors = new ArrayList<>();
        Map<String, String> values = arguments == null
                ? Collections.<String, String>emptyMap() : arguments;
        Set<String> declared = new HashSet<>();
        for (ToolParameter parameter : parameters) {
            declared.add(parameter.name);
            if (!values.containsKey(parameter.name)) {
                if (parameter.required) {
                    errors.add(new ValidationResult.Error(ValidationResult.CODE_MISSING,
                            parameter.name, "缺少必填参数"));
                }
                continue;
            }
            String value = values.get(parameter.name);
            if (value == null) {
                errors.add(new ValidationResult.Error(ValidationResult.CODE_NULL,
                        parameter.name, "参数值不能为 null"));
                continue;
            }
            validateValue(parameter, value, errors);
        }
        for (String name : values.keySet()) {
            if (name == null || !declared.contains(name)) {
                errors.add(new ValidationResult.Error(ValidationResult.CODE_UNKNOWN_PARAMETER,
                        name, "参数未在工具声明中登记"));
            }
        }
        return errors.isEmpty() ? ValidationResult.valid() : ValidationResult.invalid(errors);
    }

    private static void validateValue(ToolParameter parameter, String value,
                                      List<ValidationResult.Error> errors) {
        if (!parameter.enumValues.isEmpty() && !parameter.enumValues.contains(value)) {
            errors.add(new ValidationResult.Error(ValidationResult.CODE_ENUM, parameter.name,
                    "参数值不在允许枚举中"));
            return;
        }
        if (ToolParameter.STRING.equals(parameter.type)) return;
        if (ToolParameter.BOOLEAN.equals(parameter.type)) {
            if (!"true".equals(value) && !"false".equals(value)) {
                errors.add(new ValidationResult.Error(ValidationResult.CODE_TYPE,
                        parameter.name, "参数必须是 true 或 false"));
            }
            return;
        }
        try {
            double parsed;
            if (ToolParameter.INTEGER.equals(parameter.type)) {
                parsed = Long.parseLong(value);
            } else {
                parsed = Double.parseDouble(value);
                if (Double.isNaN(parsed) || Double.isInfinite(parsed)) throw new NumberFormatException();
            }
            if (parameter.minimum != null && parsed < parameter.minimum
                    || parameter.maximum != null && parsed > parameter.maximum) {
                errors.add(new ValidationResult.Error(ValidationResult.CODE_RANGE, parameter.name,
                        "参数超出声明范围"));
            }
        } catch (NumberFormatException ex) {
            errors.add(new ValidationResult.Error(ValidationResult.CODE_TYPE, parameter.name,
                    "参数类型不匹配: " + parameter.type));
        }
    }

    public String parameterSchemaJson() {
        StringBuilder properties = new StringBuilder("{");
        List<String> required = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) properties.append(',');
            ToolParameter parameter = parameters.get(i);
            properties.append(quote(parameter.name)).append(':').append(parameter.toJson());
            if (parameter.required) required.add(quote(parameter.name));
        }
        properties.append('}');
        StringBuilder out = new StringBuilder("{\"type\":\"object\",\"properties\":")
                .append(properties).append(",\"additionalProperties\":false");
        if (!required.isEmpty()) {
            out.append(",\"required\":[");
            for (int i = 0; i < required.size(); i++) {
                if (i > 0) out.append(',');
                out.append(required.get(i));
            }
            out.append(']');
        }
        return out.append('}').toString();
    }

    private static String quote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
