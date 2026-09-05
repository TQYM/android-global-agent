package com.dsh.agent;

import android.content.Context;
import android.content.SharedPreferences;

/** 配置持久化（SharedPreferences）。 */
public final class Prefs {
    private static final String FILE = "agent_prefs";

    public final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String baseUrl() {
        return sp.getString("base_url", "https://open.bigmodel.cn/api/paas/v4");
    }
    public String apiKey()      { return sp.getString("api_key", ""); }
    public String model()       { return sp.getString("model", "glm-4.5v"); }
    public String asrModel()    { return sp.getString("asr_model", "glm-asr-2512"); }
    public boolean vision()     { return sp.getBoolean("vision", true); }
    public int maxSteps()       { return sp.getInt("max_steps", 20); }

    public String systemPrompt() {
        String saved = sp.getString("system_prompt", null);
        if (saved != null && saved.contains("用 done 报告并请求用户确认")) {
            // 迁移：敏感操作确认从 done 升级为 ask（真正的交互式提问）
            saved = saved.replace("用 done 报告并请求用户确认，不要自己执行",
                    "用 ask 动作向用户提问确认，得到肯定回答再执行");
            sp.edit().putString("system_prompt", saved).apply();
        }
        return sp.getString("system_prompt",
                "你是手机上的智能语音助手（类似小布/小爱）。工作方式：" +
                "1) 直达优先：打开设置页用 setting 一步到位，开关 WiFi/蓝牙用专用动作打开开关面板，" +
                "能不点界面就不点。2) 界面操作是兜底：点击用节点 index，找不到先 scroll 或搜索，" +
                "不猜坐标。3) 页面加载中先 wait，不要盲点。4) 弹窗、广告、权限请求优先点关闭/跳过/" +
                "拒绝，除非任务是授权本身。5) 幂等：开关类任务先看当前状态，已是目标态直接 done。" +
                "6) 支付、转账、发消息、删数据等不可逆操作前，用 ask 动作向用户提问确认，得到肯定回答再执行。" +
                "根据屏幕语义节点和截图，一步步完成用户任务。只输出一个 JSON 动作。");
    }

    public void save(String baseUrl, String apiKey, String model, String asrModel,
                     boolean vision, int maxSteps, String systemPrompt) {
        sp.edit()
                .putString("base_url", baseUrl)
                .putString("api_key", apiKey)
                .putString("model", model)
                .putString("asr_model", asrModel)
                .putBoolean("vision", vision)
                .putInt("max_steps", maxSteps)
                .putString("system_prompt", systemPrompt)
                .apply();
    }

    public void setApiKey(String key) { sp.edit().putString("api_key", key).apply(); }

    /** Root 模式: auto(自动检测) / on(强制启用) / off(强制关闭)。 */
    public String rootMode() { return sp.getString("root_mode", "auto"); }
    public void setRootMode(String m) { sp.edit().putString("root_mode", m).apply(); }
}
