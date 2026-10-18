// SPDX-License-Identifier: Apache-2.0
package org.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 仅提供 JVM 运行环境缺少的 org.json 编解码；不包含被测业务逻辑。 */
final class JsonCodec {
    private final String text;
    private int offset;
    private JsonCodec(String text) { this.text = text; }

    static Object parse(String text) {
        JsonCodec parser = new JsonCodec(text);
        Object value = parser.value();
        parser.space();
        if (parser.offset != text.length()) throw parser.error("trailing characters");
        return value;
    }
    private IllegalArgumentException error(String reason) {
        return new IllegalArgumentException(reason + " at " + offset);
    }
    private void space() {
        while (offset < text.length() && Character.isWhitespace(text.charAt(offset))) offset++;
    }
    private boolean consume(char c) {
        space();
        if (offset < text.length() && text.charAt(offset) == c) { offset++; return true; }
        return false;
    }
    private void require(char c) {
        if (!consume(c)) throw error("expected " + c);
    }
    private Object value() {
        space();
        if (offset == text.length()) throw error("expected value");
        char c = text.charAt(offset);
        if (c == '{') {
            offset++;
            Map<String, Object> values = new LinkedHashMap<>();
            if (consume('}')) return new JSONObject(values);
            do {
                space();
                if (offset == text.length() || text.charAt(offset) != '"') throw error("expected key");
                String key = string();
                require(':');
                values.put(key, value());
            } while (consume(','));
            require('}');
            return new JSONObject(values);
        }
        if (c == '[') {
            offset++;
            List<Object> values = new ArrayList<>();
            if (consume(']')) return new JSONArray(values);
            do { values.add(value()); } while (consume(','));
            require(']');
            return new JSONArray(values);
        }
        if (c == '"') return string();
        for (String literal : new String[] {"true", "false", "null"}) {
            if (text.startsWith(literal, offset)) {
                offset += literal.length();
                return "null".equals(literal) ? null : Boolean.valueOf(literal);
            }
        }
        int start = offset;
        if (c == '-') offset++;
        while (offset < text.length() && Character.isDigit(text.charAt(offset))) offset++;
        boolean decimal = false;
        if (offset < text.length() && text.charAt(offset) == '.') {
            decimal = true;
            offset++;
            while (offset < text.length() && Character.isDigit(text.charAt(offset))) offset++;
        }
        if (offset < text.length() && (text.charAt(offset) == 'e' || text.charAt(offset) == 'E')) {
            decimal = true;
            offset++;
            if (offset < text.length() && (text.charAt(offset) == '+' || text.charAt(offset) == '-')) offset++;
            while (offset < text.length() && Character.isDigit(text.charAt(offset))) offset++;
        }
        if (start == offset) throw error("unexpected character");
        String number = text.substring(start, offset);
        if (decimal) return Double.valueOf(number);
        return Long.valueOf(number);
    }
    private String string() {
        require('"');
        StringBuilder out = new StringBuilder();
        while (offset < text.length()) {
            char c = text.charAt(offset++);
            if (c == '"') return out.toString();
            if (c != '\\') {
                if (c < 32) throw error("unescaped control character");
                out.append(c);
                continue;
            }
            if (offset == text.length()) throw error("truncated escape");
            char escaped = text.charAt(offset++);
            switch (escaped) {
                case '"': case '\\': case '/': out.append(escaped); break;
                case 'b': out.append('\b'); break;
                case 'f': out.append('\f'); break;
                case 'n': out.append('\n'); break;
                case 'r': out.append('\r'); break;
                case 't': out.append('\t'); break;
                case 'u':
                    if (offset + 4 > text.length()) throw error("truncated unicode escape");
                    out.append((char) Integer.parseInt(text.substring(offset, offset + 4), 16));
                    offset += 4;
                    break;
                default: throw error("invalid escape");
            }
        }
        throw error("unterminated string");
    }
    private static String write(Object value) {
        if (value == null) return "null";
        if (value instanceof JSONObject || value instanceof JSONArray
                || value instanceof Number || value instanceof Boolean) return value.toString();
        return quote(String.valueOf(value));
    }
    static String object(Map<String, Object> values) {
        StringBuilder out = new StringBuilder("{");
        String separator = "";
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            out.append(separator).append(quote(entry.getKey())).append(':').append(write(entry.getValue()));
            separator = ",";
        }
        return out.append('}').toString();
    }
    static String array(List<Object> values) {
        StringBuilder out = new StringBuilder("[");
        String separator = "";
        for (Object value : values) {
            out.append(separator).append(write(value));
            separator = ",";
        }
        return out.append(']').toString();
    }
    private static String quote(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 32) out.append(String.format("\\u%04x", (int) c)); else out.append(c);
            }
        }
        return out.append('"').toString();
    }
}
