// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.content.Context;
import android.content.SharedPreferences;

/** 配置持久化（SharedPreferences）。 */
public final class Prefs {
    private static final String FILE = "agent_prefs";

    /** 旧版账号令牌的明文键（迁移用，见 {@link #accountToken()}）。 */
    private static final String LEGACY_ACCOUNT_TOKEN = "account_token";
    private static final String TAG = "AgentLitePrefs";

    public final SharedPreferences sp;

    /** 账号令牌的加密存储（Keystore AES-GCM），不再走明文 SharedPreferences。 */
    private final TokenStore tokens;

    public Prefs(Context ctx) {
        sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        tokens = new TokenStore(ctx);
    }

    public String baseUrl() {
        return sp.getString("base_url", DevConfig.BASE_URL);
    }
    public String apiKey()      { return sp.getString("api_key", DevConfig.API_KEY); }
    public String model()       { return sp.getString("model", DevConfig.MODEL); }
    // asrModel() 见下方「单模型模式」节（受单模型收敛影响）
    public boolean vision()     { return sp.getBoolean("vision", true); }

    /**
     * **无限制模式**：自动同意「服务条款 / 隐私政策 / 用户协议」弹窗（默认**关闭**）。
     *
     * <p>⚠️ 开启意味着 Agent 会替你点「同意并继续」，即**代替你作出法律授权**，而这类同意
     * 通常**不可逆**（你等于接受了未曾阅读的条款）。2026-09-18 用户曾要求默认开启；
     * 2026-10-02 审计 P1-5 把默认值收回**关闭**：与敏感场景人工确认的安全默认对齐，
     * 需要代点的用户须显式开启（历史已显式存过 true 的设备不受影响）。
     *
     * <p>注意范围：只覆盖**协议类**弹窗。支付/转账/验证码/删除数据等其它红线各有其规则，
     * 不受本开关影响。
     */
    public boolean autoAcceptTerms() { return sp.getBoolean("auto_accept_terms", false); }

    public void setAutoAcceptTerms(boolean v) { sp.edit().putBoolean("auto_accept_terms", v).apply(); }

    /** M0-01 成熟模块开关的统一读取入口；默认值由 FeatureFlags 集中定义。 */
    public FeatureFlags featureFlags() { return new FeatureFlags(sp); }

    /**
     * 操作记忆库是否开启（**默认开启**）。
     *
     * <p>默认开是刻意的：用户要的是「每一步成功执行的操作自动进记忆库，之后直接复现就能完成
     * 操作」——需要先去设置里打开才算数的话，等于没做。关掉即回到「每次都重新规划」，
     * 已记录的内容仍留在库里可查可删（见「记忆库」页）。
     */
    public boolean flowMemory() { return sp.getBoolean("flow_memory", true); }

    public void setFlowMemory(boolean on) { sp.edit().putBoolean("flow_memory", on).apply(); }

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
        // 保存/清空配置不等于切换付费账户；先固定修改前的有效选择。
        boolean own = credentialRoute(model()).channel == CredentialRoute.Channel.OWN_KEY;
        sp.edit()
                .putBoolean("use_own_key", own)
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

    // ------------------------------------------------------------------
    // 单模型模式（充值改版配套，2026-09-13）
    //
    // 起因是一条会被用户察觉为「白花钱」的规则：百炼的资源包**按模型名严格匹配、
    // 跨版本或子型号不通用**（qwen-plus 资源包不抵扣 qwen-max）。App 原本会用到
    // 主模型 + 规划模型 + 视觉搭档 + 语音模型，买了一个模型的资源包就只能抵扣其中一个，
    // 其余仍走按量付费；账户余额不足时整条链路直接失败。
    //
    // 收敛方式是**运行期覆盖，不改存档值**——用户关掉开关即可回到原配置，
    // 不必重新填一遍。这是刻意的：静默改写用户的配置比不收敛更糟。
    // ------------------------------------------------------------------

    /** 是否启用单模型模式。 */
    public boolean singleModelMode() { return sp.getBoolean("single_model_mode", true); }

    public void setSingleModelMode(boolean on) { sp.edit().putBoolean("single_model_mode", on).apply(); }

    // ------------------------------------------------------------------
    // 官方购买模式（充值改版配套，2026-09-13）
    //
    // 用户走完「跳百炼官方购买 → 自动创建 API Key」后置位。**只在真的拿到并校验过 Key
    // 之后才置位**——否则会出现"标记为官方购买、但 Key 不可用、网关又已被让路"的
    // 中间态：那是最糟的结果（既不能走我方额度，也不能直连）。
    // ------------------------------------------------------------------

    /**
     * 旧偏好读取接口（默认 true 不代表用户已作选择）。
     * 任务路由统一使用 credentialRoute：未保存选择时保留有 Key 直连、无 Key 账号的历史规则；
     * 显式 true/false 则固定付费主体，配置缺失或请求失败都不会改用另一来源。
     */
    public boolean useOwnKey() { return sp.getBoolean("use_own_key", true); }

    public void setUseOwnKey(boolean on) { sp.edit().putBoolean("use_own_key", on).apply(); }

    /** 用户在选择卡上主动选择通道；选择账号时同时解除旧版网关关闭标志。 */
    public void selectCredentialChannel(boolean own) {
        SharedPreferences.Editor e = sp.edit().putBoolean("use_own_key", own);
        if (!own) e.putBoolean("use_gateway", true);
        e.apply();
    }

    /** 一次解析中各凭据只读一次；不写选择、不猜余额、不访问模型。 */
    public CredentialRoute credentialRoute(String requestedModel) {
        return CredentialRoute.resolve(sp.contains("use_own_key"), useOwnKey(),
                sp.getBoolean("use_gateway", true), baseUrl(), apiKey(), requestedModel,
                accountId(), accountToken(), accountInvalid(), syncServer());
    }

    /**
     * 原始规划模型存档值（**不**受单模型模式影响），仅供设置页回填输入框。
     *
     * <p>单模型模式下必须用它而不是 {@link #plannerModel()}（后者会返回主模型），
     * 否则用户一保存就会把主模型名写进 `planner_model`，把原配置永久覆盖掉。
     */
    public String plannerModelRaw() { return sp.getString("planner_model", ""); }

    /**
     * 原始语音模型存档值（**不**受单模型模式影响），仅供设置页回填输入框。
     *
     * <p>与 {@link #plannerModelRaw()} 同理：用 {@link #asrModel()} 回填会导致
     * 「打开设置页 → 点保存」这一步把收敛后的主模型名写进存档。
     */
    public String asrModelRaw() {
        String v = sp.getString("asr_model", "");
        // 2026-09-18：glm-asr-2512 现在是**有效**的云端 ASR 模型（智谱），不再是"失效默认值"，
        // 不能再被清空——否则设置页回填为空，用户看不到自己配过什么。
        return v == null ? "" : v;
    }

    /** 视觉搭档模型：空 = 与主模型相同。 */
    public String visionModel() { return converge("vision_model"); }

    /**
     * 语义规划模型：把口语任务转成结构化简报；空 = 关闭规划前置。
     *
     * <p>历史默认值是 `qwen3.5-flash`。单模型模式下**返回主模型而不是空**——
     * 返回空会让 `AgentEngine` 直接跳过规划，等于把功能**静默关掉**，
     * 用户只会觉得"怎么变笨了"。返回主模型则规划照常工作，只是换了执行者。
     */
    public String plannerModel() {
        if (singleModelMode()) return model();
        String v = plannerModelRaw();
        return v.isEmpty() ? "qwen3.5-flash" : v;
    }

    /** 写入规划模型。单模型模式下**忽略**，避免主模型名覆盖用户原配置。 */
    public void setPlannerModel(String m) {
        if (singleModelMode()) return;
        sp.edit().putString("planner_model", m == null ? "" : m.trim()).apply();
    }

    /** 跨设备同步下发的规划模型：同样受单模型模式保护。 */
    public void setPlannerModelFromSync(String m) { setPlannerModel(m); }

    /**
     * 单模型收敛：启用时**一律返回主模型**，无论用户配了什么。
     *
     * <p>两条刻意的取舍：
     * <ul>
     *   <li><b>只覆盖返回值，绝不动存档</b>。用户关掉开关，原配置一字不差地回来，
     *       不必重填。静默改写用户配置比不收敛更糟。</li>
     *   <li><b>不因"用户配了多模型"而自动退出单模型模式</b>。曾经这么设计过，但那是自相矛盾的——
     *       该模式的全部意义就是强制单模型；"配了别的就放行"会让功能永远不生效。
     *       想用多模型就明确关掉开关，选择权留在用户手里，而不是被代码猜。</li>
     * </ul>
     */
    private String converge(String key) {
        if (singleModelMode()) return model();
        String v = sp.getString(key, "");
        return v == null ? "" : v;
    }

    /**
     * 指定远端 ASR 的模型名；本地模式不使用此项，不借主模型音频能力。
     */
    public String asrModel() {
        String v = sp.getString("asr_model", "");
        return v == null ? "" : v;
    }

    /**
     * ASR 模式唯一选择：端点为空使用本地；非空仅请求指定 ASR 端点。
     *
     * <p>存在的原因见 {@code LlmClient#transcribe}：语音曾经复用主模型的音频能力，上游换成
     * DeepSeek 后该能力消失，语音输入就整条失效且用户毫无提示。独立端点让 ASR 与 LLM 解耦，
     * 可以指向局域网离线转写服务（{@code tools/asr_server.py}）或任何 OpenAI 兼容 ASR。
     */
    public String asrBaseUrl() {
        String v = sp.getString("asr_base_url", "");
        return v == null ? "" : v.trim();
    }

    /** 独立 ASR 密钥；空就不发鉴权头，适用于免密本地服务，不借主 key。 */
    public String asrApiKey() {
        String v = sp.getString("asr_api_key", "");
        return v == null ? "" : v.trim();
    }

    /**
     * 保存指定远端 ASR 配置；只有端点决定模式，失败由调用方展示，不切换本地。
     *
     * <p>典型值（智谱 GLM）：端点 {@code https://open.bigmodel.cn/api/paas/v4}、
     * 模型 {@code glm-asr-2512}（填在 asr_model）。接口是 OpenAI 兼容的
     * {@code POST /audio/transcriptions}（multipart: file + model），与 LlmClient 现有实现一致。
     */
    public void setAsrCloud(String baseUrl, String apiKey) {
        sp.edit()
                .putString("asr_base_url", baseUrl == null ? "" : baseUrl.trim())
                .putString("asr_api_key", apiKey == null ? "" : apiKey.trim())
                .apply();
    }

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
        synchronized (sp) { sp.edit().putString("sync_device_id", id).apply(); }
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
    // 令牌**加密存 Keystore**（TokenStore，AES-GCM），服务端只存 sha256；
    // 每次登录都会轮换令牌。

    /**
     * 账号令牌：注册/登录成功后由服务端下发。
     *
     * <p>读取时会顺带完成一次性迁移：旧版把令牌明文写在 {@code account_token}，
     * 这里尝试「加密落盘 → 删除明文」，**只有加密成功才删除**——Keystore 不可用时
     * 宁可暂时留着旧值，也不让用户凭空掉线（下次读取会再试一次）。
     */
    public String accountToken() {
        String token = tokens.load();
        if (!token.isEmpty()) return token;
        String legacy = sp.getString(LEGACY_ACCOUNT_TOKEN, "");
        if (legacy == null || legacy.isEmpty()) return "";
        if (tokens.save(legacy)) {
            sp.edit().remove(LEGACY_ACCOUNT_TOKEN).apply();
            android.util.Log.i(TAG, "账号令牌已迁移到 Keystore 加密存储");
        } else {
            android.util.Log.w(TAG, "令牌明文迁移未完成（加密不可用），保留旧存储待重试");
        }
        return legacy;
    }

    public String accountId() { return sp.getString("account_id", ""); }
    public String accountName() { return sp.getString("account_name", ""); }

    /**
     * 是否已登录（令牌与账号 ID 齐备，且令牌**没有被服务端判失效**）。
     *
     * <p>2026-09-15 真机实测的可用性缺陷：令牌失效（401）后本地字符串还在，
     * 旧实现仍返回 true → LoginActivity 认为"已登录"直接 finish()，用户点
     * 「重新登录」被弹回失效提示页，**永远无法自救**。令牌已被判定失效就不再算登录态。
     */
    public boolean loggedIn() {
        return !accountToken().isEmpty() && !accountId().isEmpty() && !accountInvalid();
    }

    /** 账号令牌被服务端判为失效（401）后置位，UI 据此提示重新登录。 */
    public boolean accountInvalid() { return sp.getBoolean("account_invalid", false); }
    public void setAccountInvalid(boolean v) { sp.edit().putBoolean("account_invalid", v).apply(); }

    /** 额度单位：1 unit = 1000 tokens（服务端记账口径）。 */
    public long accountQuota() { return sp.getLong("account_quota", 0L); }
    public long accountQuotaUsed() { return sp.getLong("account_quota_used", 0L); }

    /**
     * 登录/注册成功的一次性落地；换账号时同步游标归零（服务端按 id 去重，不会重复入库）。
     *
     * @return true = 令牌已加密落盘；false = 安全存储不可用（**没有**任何明文落盘，
     *         调用方应提示用户，不要假装登录成功）
     */
    public boolean saveAccount(String id, String name, String token, long quota, long quotaUsed) {
        synchronized (sp) {
        if (token == null || token.isEmpty()) {
            android.util.Log.w(TAG, "账号令牌为空，拒绝保存登录态");
            return false;
        }
        // 先读出旧令牌：如果后面的偏好提交失败，需要尽力回滚，避免账号元数据与令牌错配。
        String oldToken = accountToken();
        boolean switched = !accountId().isEmpty() && !accountId().equals(id);
        if (!tokens.save(token)) {
            // 令牌加密失败时，保持旧令牌和旧账号元数据不变，避免留下半登录状态。
            return false;
        }
        SharedPreferences.Editor e = sp.edit()
                .putString("account_id", id)
                .putString("account_name", name)
                // 明文位置从此不再写入；顺手清掉可能残留的旧值
                .remove(LEGACY_ACCOUNT_TOKEN)
                .putLong("account_quota", quota)
                .putLong("account_quota_used", quotaUsed)
                .putBoolean("account_invalid", false);
        if (switched) {
            e.putLong("sync_cursor", 0L);
            e.putInt("sync_history_index", 0);
            e.putInt("sync_memory_index", 0);
            e.putInt("sync_peer_index", 0);
            e.putInt("sync_config_version", 0);
            e.putString("peer_devices", "[]");
            e.putString("sync_device_id", "");   // 换账号需重新绑定本机设备
        }
        if (e.commit()) {
            return true;
        }
        // SharedPreferences 提交失败时回滚令牌；失败仍按未登录处理，不返回假成功。
        if (oldToken == null || oldToken.isEmpty()) tokens.clear();
        else tokens.save(oldToken);
        android.util.Log.w(TAG, "账号元数据保存失败，已回滚令牌");
        return false;
        }
    }

    /** 只刷新额度（/account/me 或兑换成功后调用）。 */
    public void setAccountQuota(long quota, long quotaUsed) {
        sp.edit().putLong("account_quota", quota).putLong("account_quota_used", quotaUsed).apply();
    }

    /** 退出登录：清空账号凭据与本机设备绑定，保留任务历史与记忆。 */
    public void clearAccount() {
        synchronized (sp) {
        // 令牌在 Keystore 侧，单独清（含删除 Keystore 别名与进程内缓存）
        tokens.clear();
        sp.edit()
                .remove(LEGACY_ACCOUNT_TOKEN)
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
                .putInt("sync_peer_index", 0)
                .putInt("sync_config_version", 0)
                .remove("peer_devices")
                .putString("sync_status", "未登录：请先登录账号")
                .apply();
        }
    }

    // ---- 异形屏适配开关（回滚保护，默认开启） ----
    /** 感知提示词注入屏幕形态（挖孔/曲面/安全区）。 */
    public boolean specInject() { return sp.getBoolean("spec_inject", true); }
    /** 手势落点 clamp 进安全触控区。 */
    public boolean specClamp()  { return sp.getBoolean("spec_clamp", true); }
    public void setSpecInject(boolean v) { sp.edit().putBoolean("spec_inject", v).apply(); }
    public void setSpecClamp(boolean v)  { sp.edit().putBoolean("spec_clamp", v).apply(); }
}
