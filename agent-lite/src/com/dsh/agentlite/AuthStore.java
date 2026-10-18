// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 事前授权存储（报告 §5.1 三层授权之事前层）。
 * 「始终允许」按 scope 持久化，scope = 操作类型 × 目标 App（当前实现粒度：app|<包名>）。
 * 事后可随时撤回（clear/clearAll），撤回后再次发起会重走授权弹窗。
 */
public final class AuthStore {
    private static final String TAG = "AuthStore";
    private static final String FILE = "agent_auth";
    private static final String PREFIX_ALWAYS = "always|";

    private final SharedPreferences sp;

    public AuthStore(Context ctx) {
        recoverIfCorrupt(ctx);
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /**
     * 加载前自检一次授权存储文件。
     * {@link SharedPreferences} 在 XML 解析失败时既不报错也不重建，只是静默返回空 map，
     * 于是「始终允许」白名单整体消失且无从察觉（真机实测到过半截覆盖损坏：
     * XML 声明之后 {@code <map>} 起始段整段丢失、{@code </map>} 重复、末尾截断在半个键名，
     * 且缺少 AtomicFile 正常路径本应留下的 .bak）。
     * 解析失败即把原文件改名备份（改名失败则删除），让本次加载按空表继续。
     * 备份保留现场以便事后取证。注意这里**不预先创建**新文件——{@code SharedPreferences}
     * 只在下次写入时落盘，所以修好后的一段时间里该文件仍不存在，这是正常的。
     */
    private static void recoverIfCorrupt(Context ctx) {
        File f = prefsFile(ctx);
        if (f == null || !f.isFile() || f.length() == 0) return;
        if (parses(f)) return;
        File bak = new File(f.getParentFile(),
                FILE + ".xml.corrupt." + System.currentTimeMillis());
        boolean kept = f.renameTo(bak);
        if (!kept) f.delete();
        Log.w(TAG, "授权白名单文件损坏，已"
                + (kept ? "备份为 " + bak.getName() : "删除（备份失败）")
                + "，白名单按空表继续：" + f);
    }

    private static File prefsFile(Context ctx) {
        return new File(new File(ctx.getApplicationInfo().dataDir, "shared_prefs"),
                FILE + ".xml");
    }

    /** 结构校验：必须能解析到 END_DOCUMENT，且首元素是 {@code <map>}。 */
    private static boolean parses(File f) {
        try (InputStream in = new FileInputStream(f)) {
            XmlPullParser p = Xml.newPullParser();
            p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false);
            p.setInput(in, null);
            boolean sawRoot = false;
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && !sawRoot) {
                    if (!"map".equals(p.getName())) return false;
                    sawRoot = true;
                }
            }
            return sawRoot;
        } catch (Throwable t) {
            return false;
        }
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
