// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite.contracts;

import java.util.List;
import java.util.Map;

/** 无第三方依赖的稳定 JSON 字段渲染器。输入值只来自契约对象。 */
public final class ContractJson {
    private ContractJson() { }

    public static String quote(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }

    public static String stringField(String name, String value) {
        return quote(name) + ":" + quote(value);
    }

    public static String booleanField(String name, boolean value) {
        return quote(name) + ":" + value;
    }

    public static String longField(String name, long value) {
        return quote(name) + ":" + value;
    }

    public static String intField(String name, int value) {
        return quote(name) + ":" + value;
    }

    public static String object(String... fields) {
        return join('{', '}', fields);
    }

    public static String array(List<String> values) {
        return join('[', ']', values.toArray(new String[0]));
    }

    public static String stringArray(List<String> values) {
        String[] fields = new String[values.size()];
        for (int i = 0; i < values.size(); i++) fields[i] = quote(values.get(i));
        return array(java.util.Arrays.asList(fields));
    }

    public static String stringMap(Map<String, String> values) {
        String[] fields = new String[values.size()];
        int i = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            fields[i++] = quote(entry.getKey()) + ":" + quote(entry.getValue());
        }
        return join('{', '}', fields);
    }

    private static String join(char open, char close, String[] fields) {
        StringBuilder out = new StringBuilder().append(open);
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) out.append(',');
            out.append(fields[i]);
        }
        return out.append(close).toString();
    }
}
