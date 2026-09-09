package com.dsh.agentlite;

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
        return sp.getString("base_url", DevConfig.BASE_URL);
    }
    public String apiKey()      { return sp.getString("api_key", DevConfig.API_KEY); }
    public String model()       { return sp.getString("model", DevConfig.MODEL); }
    public String asrModel()    { String v = sp.getString("asr_model", "");
        if ("glm-asr-2512".equals(v)) v = "";   // 旧默认值已失效，自动迁移为空（=用主模型转写）
        return v; }
    public boolean vision()     { return sp.getBoolean("vision", true); }
    public int maxSteps()       {
        int v = sp.getInt("max_steps", 50);
        if (v == 20 || v == 30 || v == 100) {
            // 自动统一为最新标准默认值 50 步
            v = 50;
            sp.edit().putInt("max_steps", 50).apply();
        }
        return v;
    }

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

    /** 累计消耗 Token 统计：Prompt Token、Completion Token、Total Token */
    public long promptTokens() { return sp.getLong("tokens_prompt", 0L); }
    public long completionTokens() { return sp.getLong("tokens_completion", 0L); }
    public long totalTokens() { return sp.getLong("tokens_total", 0L); }

    public synchronized void addTokens(long prompt, long completion, long total) {
        if (total <= 0 && prompt + completion > 0) total = prompt + completion;
        sp.edit()
                .putLong("tokens_prompt", promptTokens() + Math.max(0, prompt))
                .putLong("tokens_completion", completionTokens() + Math.max(0, completion))
                .putLong("tokens_total", totalTokens() + Math.max(0, total))
                .apply();
    }

    public void resetTokens() {
        sp.edit()
                .putLong("tokens_prompt", 0L)
                .putLong("tokens_completion", 0L)
                .putLong("tokens_total", 0L)
                .apply();
    }

    /** Root 模式: auto(自动检测) / on(强制启用) / off(强制关闭)。Lite 版无 UI，默认 auto 静默加速。 */
    public String rootMode() { return sp.getString("root_mode", "auto"); }
    /** 视觉搭档模型：空 = 与主模型相同。 */
    public String visionModel() { return sp.getString("vision_model", ""); }
    /** 语义规划模型：把口语任务转成结构化简报；空 = 关闭规划前置。（qwen3.8flash 不存在，实测可用默认 qwen3.5-flash） */
    public String plannerModel() { return sp.getString("planner_model", "qwen3.5-flash"); }
    public void setPlannerModel(String m) { sp.edit().putString("planner_model", m).apply(); }

    public void setRootMode(String m) { sp.edit().putString("root_mode", m).apply(); }
}
