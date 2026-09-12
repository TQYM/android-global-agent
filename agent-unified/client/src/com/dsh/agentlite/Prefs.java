// SPDX-License-Identifier: Apache-2.0

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

    // ---- 远程信息同步（agentlite-server） ----
    /** 同步总开关。默认开启；服务器地址为空时同步自动静默跳过。 */
    /**
     * 是否允许把本机历史/记忆上传到同步服务（守则 S-4：同步必须默认关闭且显式 opt-in）。
     *
     * 三态语义：
     *   ① 用户**显式拨过**开关 → 一律以开关为准；
     *   ② 从未拨过且**未登录** → 关闭（新装默认：数据只留本机）；
     *   ③ 从未拨过但**已登录** → 视为登录时已显式授权（避免升级后静默中断既有用户的同步）。
     *
     * 注：`SyncClient` 本身要求已登录才会上行，因此 ② 相对旧默认（true）**行为完全一致**——
     * 这次改动只是把隐私默认写明，并补上此前缺失的 UI 入口。
     */
    public boolean syncEnabled() {
        if (sp.contains("sync_enabled")) return sp.getBoolean("sync_enabled", false);
        return loggedIn();
    }
    public void setSyncEnabled(boolean v) { sp.edit().putBoolean("sync_enabled", v).apply(); }

    public String syncServer() { return sp.getString("sync_server", DevConfig.SYNC_URL); }
    public void setSyncServer(String u) { sp.edit().putString("sync_server", u).apply(); }

    public String syncDeviceId() { return sp.getString("sync_device_id", ""); }

    /** 本机设备 ID（LanSync 也直接读这个键）。 */
    public void setSyncDeviceId(String id) {
        sp.edit().putString("sync_device_id", id).apply();
    }

    /** 服务端记录游标（server_seq），用于增量拉取。 */
    public long syncCursor() { return sp.getLong("sync_cursor", 0L); }
    public void setSyncCursor(long v) { sp.edit().putLong("sync_cursor", v).apply(); }

    /** 已下发的远程配置版本，避免同一版本反复应用。 */
    public int syncConfigVersion() { return sp.getInt("sync_config_version", 0); }
    public void setSyncConfigVersion(int v) { sp.edit().putInt("sync_config_version", v).apply(); }

    /** 本地已上传的任务历史行数 / 记忆条数（增量游标）。 */
    public int syncHistoryIndex() { return sp.getInt("sync_history_index", 0); }
    public void setSyncHistoryIndex(int v) { sp.edit().putInt("sync_history_index", v).apply(); }
    public int syncMemoryIndex() { return sp.getInt("sync_memory_index", 0); }
    public void setSyncMemoryIndex(int v) { sp.edit().putInt("sync_memory_index", v).apply(); }

    /** 最近一次同步结果（供设置页展示）与成功时间戳。 */
    public String syncStatus() { return sp.getString("sync_status", "尚未同步"); }
    public void setSyncStatus(String s) { sp.edit().putString("sync_status", s).apply(); }
    public long syncLastOk() { return sp.getLong("sync_last_ok", 0L); }
    public void setSyncLastOk(long v) { sp.edit().putLong("sync_last_ok", v).apply(); }

    // ---- 账号体系（agentlite-server） ----
    // 鉴权模型：账号令牌（Bearer）覆盖全部业务接口，设备用 X-Device-Id 归属到账号。
    // 令牌明文只存本机 SharedPreferences，服务端只存 sha256；每次登录都会轮换令牌。

    /** 账号令牌：注册/登录成功后由服务端下发。 */
    public String accountToken() { return sp.getString("account_token", ""); }
    public String accountId() { return sp.getString("account_id", ""); }
    public String accountName() { return sp.getString("account_name", ""); }

    /** 是否已登录（令牌与账号 ID 齐备，缺一即视为未登录）。 */
    public boolean loggedIn() {
        return !accountToken().isEmpty() && !accountId().isEmpty();
    }

    /** 账号令牌被服务端判为失效（401）后置位，UI 据此提示重新登录。 */
    public boolean accountInvalid() { return sp.getBoolean("account_invalid", false); }
    public void setAccountInvalid(boolean v) { sp.edit().putBoolean("account_invalid", v).apply(); }

    /** 额度单位：1 unit = 1000 tokens（服务端记账口径）。 */
    public long accountQuota() { return sp.getLong("account_quota", 0L); }
    public long accountQuotaUsed() { return sp.getLong("account_quota_used", 0L); }

    /** 登录/注册成功的一次性落地；换账号时同步游标归零（服务端按 id 去重，不会重复入库）。 */
    public void saveAccount(String id, String name, String token, long quota, long quotaUsed) {
        boolean switched = !accountId().isEmpty() && !accountId().equals(id);
        SharedPreferences.Editor e = sp.edit()
                .putString("account_id", id)
                .putString("account_name", name)
                .putString("account_token", token)
                .putLong("account_quota", quota)
                .putLong("account_quota_used", quotaUsed)
                .putBoolean("account_invalid", false);
        if (switched) {
            e.putLong("sync_cursor", 0L);
            e.putInt("sync_history_index", 0);
            e.putInt("sync_memory_index", 0);
            e.putString("sync_device_id", "");   // 换账号需重新绑定本机设备
        }
        e.apply();
    }

    /** 只刷新额度（/account/me 或兑换成功后调用）。 */
    public void setAccountQuota(long quota, long quotaUsed) {
        sp.edit().putLong("account_quota", quota).putLong("account_quota_used", quotaUsed).apply();
    }

    /** 退出登录：清空账号凭据与本机设备绑定，保留任务历史与记忆。 */
    public void clearAccount() {
        sp.edit()
                .remove("account_token")
                .remove("account_id")
                .remove("account_name")
                .remove("account_quota")
                .remove("account_quota_used")
                .remove("sync_device_id")
                // 派生自账号密钥的局域网密钥随登录态一起丢弃（下次登录会重新派生）；
                // 调用方同时应调 LanSync.stop() 关掉监听（见 LoginActivity/SettingsActivity 的退出登录）
                .remove("lan_key")
                .putBoolean("account_invalid", false)
                .putLong("sync_cursor", 0L)
                .putInt("sync_history_index", 0)
                .putInt("sync_memory_index", 0)
                .putString("sync_status", "未登录：请先登录账号")
                .apply();
    }

    // ---- 异形屏适配开关（回滚保护，默认开启） ----
    /** 感知提示词注入屏幕形态（挖孔/曲面/安全区）。 */
    public boolean specInject() { return sp.getBoolean("spec_inject", true); }
    /** 手势落点 clamp 进安全触控区。 */
    public boolean specClamp()  { return sp.getBoolean("spec_clamp", true); }
    public void setSpecInject(boolean v) { sp.edit().putBoolean("spec_inject", v).apply(); }
    public void setSpecClamp(boolean v)  { sp.edit().putBoolean("spec_clamp", v).apply(); }
}
