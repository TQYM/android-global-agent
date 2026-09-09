package com.dsh.agentlite;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 生态可达性清单（报告 §5.3.2 / §9.3.1）：App 三档梯度，数据化、可编辑、禁止写死。
 *  - AUTO       可全自动：可登录且可被 AI 完整操作
 *  - LOGIN_ONLY 可登录但不可 AI 操作：用户可手动用，AI 不得代操作（有替代 App 则自动切换）
 *  - BLOCKED    受限：应用风控屏蔽无障碍节点——降级为纯视觉模式（截图+模拟点击），
 *               用户授权后尝试，失败如实归因
 *
 * 默认数据为 2025-12-10 新京报 23 款实测快照（报告 §9.3.1），属于历史时点数据，
 * 清单会随生态变化，用户可在「生态」管理页逐条改档。
 */
public final class EcoPolicy {
    public static final int AUTO = 0;
    public static final int LOGIN_ONLY = 1;
    public static final int BLOCKED = 2;

    public static final class Entry {
        public String name;     // 中文名（展示 + 任务文本扫描）
        public String pkg;      // 包名
        public int tier;
        public String altName;  // 替代应用名（可空）
        public String altPkg;   // 替代应用包名（可空）
    }

    private static final String FILE = "agent_eco";
    private final SharedPreferences sp;

    public EcoPolicy(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        // 清理旧版历史阻断数据，确保用户已安装的购物与工具 App 默认为全自动
        if (sp.contains("json")) {
            sp.edit().remove("json").apply();
        }
    }

    /** 全部条目（默认全部应用为 AUTO 可全自动模式）。 */
    public List<Entry> entries() {
        String json = sp.getString("json", null);
        if (json != null) {
            try {
                JSONArray arr = new JSONArray(json);
                List<Entry> out = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Entry e = new Entry();
                    e.name = o.optString("name");
                    e.pkg = o.optString("pkg");
                    e.tier = o.optInt("tier");
                    e.altName = o.optString("altName", null);
                    e.altPkg = o.optString("altPkg", null);
                    out.add(e);
                }
                return out;
            } catch (Exception ignored) { }
        }
        return defaults();
    }

    public void save(List<Entry> list) {
        try {
            JSONArray arr = new JSONArray();
            for (Entry e : list) {
                JSONObject o = new JSONObject();
                o.put("name", e.name);
                o.put("pkg", e.pkg);
                o.put("tier", e.tier);
                if (e.altName != null) o.put("altName", e.altName);
                if (e.altPkg != null) o.put("altPkg", e.altPkg);
                arr.put(o);
            }
            sp.edit().putString("json", arr.toString()).apply();
        } catch (Exception ignored) { }
    }

    public void reset() { sp.edit().remove("json").apply(); }

    /** 按包名或中文名查条目；未登记返回 null（=默认可全自动）。 */
    public Entry find(String pkgOrName) {
        if (pkgOrName == null) return null;
        for (Entry e : entries()) {
            if (e.pkg.equals(pkgOrName) || e.name.equals(pkgOrName)) return e;
        }
        return null;
    }

    /** 二档失败话术（报告 §4.3.2 范式：任务失败 + 实验室功能自我定位 + 归因）。 */
    public String loginOnlyMsg(Entry e) {
        return "本功能属于实验室能力，现不支持对「" + e.name
                + "」的 AI 操作（可手动登录使用）。如需继续，请手动完成该环节";
    }

    /** 三档话术：受限应用纯视觉模式也走不通时的归因。 */
    public String blockedMsg(Entry e) {
        return "「" + e.name
                + "」屏蔽了无障碍读取，纯视觉模式（截图+模拟点击）下仍无法完成，请手动使用该应用";
    }

    public static String tierLabel(int tier) {
        switch (tier) {
            case LOGIN_ONLY: return "可登录·不可AI操作";
            case BLOCKED: return "受限·纯视觉尝试";
            default: return "可全自动";
        }
    }

    /** 默认清单：所有主流购物、生活与通讯 App 默认全部开放可全自动直达与操作。 */
    private static List<Entry> defaults() {
        List<Entry> l = new ArrayList<>();
        l.add(e("淘宝", "com.taobao.taobao", AUTO, null, null));
        l.add(e("京东", "com.jingdong.app.mall", AUTO, null, null));
        l.add(e("拼多多", "com.xunmeng.pinduoduo", AUTO, null, null));
        l.add(e("闲鱼", "com.taobao.idlefish", AUTO, null, null));
        l.add(e("美团", "com.sankuai.meituan", AUTO, null, null));
        l.add(e("美团外卖", "com.sankuai.meituan.takeoutnew", AUTO, null, null));
        l.add(e("微信", "com.tencent.mm", AUTO, null, null));
        l.add(e("支付宝", "com.eg.android.AlipayGphone", AUTO, null, null));
        l.add(e("高德地图", "com.autonavi.minimap", AUTO, null, null));
        return l;
    }

    private static Entry e(String name, String pkg, int tier, String altName, String altPkg) {
        Entry en = new Entry();
        en.name = name; en.pkg = pkg; en.tier = tier;
        en.altName = altName; en.altPkg = altPkg;
        return en;
    }
}
