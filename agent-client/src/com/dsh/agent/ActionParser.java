package com.dsh.agent;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 从 LLM 回复中稳健地抽取单个 JSON 动作对象。 */
public final class ActionParser {
    private static final Pattern OBJ = Pattern.compile("\\{[^{}]*\\}");

    private ActionParser() { }

    public static JSONObject parse(String reply) throws JSONException {
        String s = reply.trim();
        if (s.startsWith("```json")) s = s.substring(7);
        if (s.startsWith("```")) s = s.substring(3);
        if (s.endsWith("```")) s = s.substring(0, s.length() - 3);
        s = s.trim();
        try {
            JSONObject o = new JSONObject(s);
            if (o.has("action")) return o;
        } catch (JSONException ignored) { }
        Matcher m = OBJ.matcher(s);
        while (m.find()) {
            try {
                JSONObject o = new JSONObject(m.group());
                if (o.has("action")) return o;
            } catch (JSONException ignored) { }
        }
        throw new JSONException("cannot parse action from reply: " +
                (reply.length() > 200 ? reply.substring(0, 200) + "..." : reply));
    }
}
