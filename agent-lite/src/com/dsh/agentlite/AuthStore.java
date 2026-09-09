package com.dsh.agentlite;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 事前授权存储（报告 §5.1 三层授权之事前层）。
 * 「始终允许」按 scope 持久化，scope = 操作类型 × 目标 App（当前实现粒度：app|<包名>）。
 * 事后可随时撤回（clear/clearAll），撤回后再次发起会重走授权弹窗。
 */
public final class AuthStore {
    private static final String FILE = "agent_auth";
    private static final String PREFIX_ALWAYS = "always|";

    private final SharedPreferences sp;

    public AuthStore(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String scopeOfApp(String pkg) { return "app|" + pkg; }

    public boolean isAlways(String scope) {
        return sp.getBoolean(PREFIX_ALWAYS + scope, false);
    }

    public void grantAlways(String scope) {
        sp.edit().putBoolean(PREFIX_ALWAYS + scope, true).apply();
    }

    public void revoke(String scope) {
        sp.edit().remove(PREFIX_ALWAYS + scope).apply();
    }

    public void clearAll() {
        sp.edit().clear().apply();
    }

    /** 已预授权的 scope 列表（供管理界面展示/撤回）。 */
    public List<String> list() {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (e.getKey().startsWith(PREFIX_ALWAYS)
                    && Boolean.TRUE.equals(e.getValue())) {
                out.add(e.getKey().substring(PREFIX_ALWAYS.length()));
            }
        }
        return out;
    }
}
