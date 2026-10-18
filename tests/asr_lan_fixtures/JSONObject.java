// SPDX-License-Identifier: Apache-2.0
package org.json;

import java.util.LinkedHashMap;
import java.util.Map;

/** JVM 回归用的可编码/可解析 JSON；业务代码原样编译，不模拟 LAN/ASR 决策。 */
public class JSONObject {
    final Map<String, Object> values;
    public JSONObject() { values = new LinkedHashMap<>(); }
    JSONObject(Map<String, Object> values) { this.values = values; }
    public JSONObject(String text) {
        Object parsed = JsonCodec.parse(text);
        if (!(parsed instanceof JSONObject)) throw new IllegalArgumentException("expected JSON object");
        values = ((JSONObject) parsed).values;
    }
    public JSONObject put(String key, Object value) {
        if (value == null) values.remove(key); else values.put(key, value);
        return this;
    }
    public boolean has(String key) { return values.containsKey(key); }
    public String optString(String key) { return optString(key, ""); }
    public String optString(String key, String defaultValue) {
        Object value = values.get(key);
        return value == null ? defaultValue : String.valueOf(value);
    }
    public String getString(String key) {
        if (!values.containsKey(key)) throw new IllegalArgumentException("missing key: " + key);
        return optString(key);
    }
    public int optInt(String key, int defaultValue) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).intValue() : defaultValue;
    }
    public long optLong(String key, long defaultValue) {
        Object value = values.get(key);
        return value instanceof Number ? ((Number) value).longValue() : defaultValue;
    }
    public boolean optBoolean(String key) { return optBoolean(key, false); }
    public boolean optBoolean(String key, boolean defaultValue) {
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : defaultValue;
    }
    public JSONObject optJSONObject(String key) {
        Object value = values.get(key);
        return value instanceof JSONObject ? (JSONObject) value : null;
    }
    public JSONArray optJSONArray(String key) {
        Object value = values.get(key);
        return value instanceof JSONArray ? (JSONArray) value : null;
    }
    public JSONObject getJSONObject(String key) {
        JSONObject result = optJSONObject(key);
        if (result == null) throw new IllegalArgumentException("expected JSON object: " + key);
        return result;
    }
    public JSONArray getJSONArray(String key) {
        JSONArray result = optJSONArray(key);
        if (result == null) throw new IllegalArgumentException("expected JSON array: " + key);
        return result;
    }
    @Override public String toString() { return JsonCodec.object(values); }
}
