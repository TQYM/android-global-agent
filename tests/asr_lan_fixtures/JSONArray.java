// SPDX-License-Identifier: Apache-2.0
package org.json;

import java.util.ArrayList;
import java.util.List;

public class JSONArray {
    final List<Object> values;
    public JSONArray() { values = new ArrayList<>(); }
    JSONArray(List<Object> values) { this.values = values; }
    public JSONArray(String text) {
        Object parsed = JsonCodec.parse(text);
        if (!(parsed instanceof JSONArray)) throw new IllegalArgumentException("expected JSON array");
        values = ((JSONArray) parsed).values;
    }
    public JSONArray put(Object value) { values.add(value); return this; }
    public int length() { return values.size(); }
    public JSONObject optJSONObject(int index) {
        Object value = index < 0 || index >= values.size() ? null : values.get(index);
        return value instanceof JSONObject ? (JSONObject) value : null;
    }
    public JSONObject getJSONObject(int index) {
        JSONObject result = optJSONObject(index);
        if (result == null) throw new IllegalArgumentException("expected JSON object at " + index);
        return result;
    }
    @Override public String toString() { return JsonCodec.array(values); }
}
