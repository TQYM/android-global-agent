// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** 原生主界面：任务输入 / 语音 / 日志 / 屏幕回显 / 动作调试台。 */
public class MainActivity extends Activity implements AgentEngine.Listener {
    private static MainActivity sInstance;
    static android.app.Application appStatic() { return sInstance.getApplication(); }

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private AgentEngine engine;
    private boolean watchUi;   // 手表形态（圆形表盘 / 方形小屏）→ activity_main_watch 布局

    /**
     * 手表形态判定（决定用哪套布局）：
     *  1) UI_MODE_TYPE_WATCH（标准 Wear 声明）；
     *  2) isScreenRound()（圆形表盘，手机无此形态）；
     *  3) 屏幕最小边 < 320dp 兜底（OWW261 466px@320dpi = 233dp；手机最小边 ≥ 320dp）。
     * 任一命中按手表 UI 渲染；判定异常回退手机布局（不崩）。
     */
    private boolean isWatchUi() {
        try {
            android.content.res.Configuration c = getResources().getConfiguration();
            if ((c.uiMode & android.content.res.Configuration.UI_MODE_TYPE_MASK)
                    == android.content.res.Configuration.UI_MODE_TYPE_WATCH) return true;
            if (c.isScreenRound()) return true;
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            if (dm.density > 0) {
                int minDp = (int) (Math.min(dm.widthPixels, dm.heightPixels) / dm.density);
                return minDp > 0 && minDp < 320;
            }
        } catch (Throwable t) { }
        return false;
    }

    private VoiceRecorder recorder;
    private Bitmap lastBmp;

    // 主页只保留：任务输入 / 运行 / 语音 / 状态 / 账号状态 / 调试台（日志与截图容器保留隐藏供内部逻辑使用）。
    // API 配置、Token 统计、设备形态、保活按钮已搬到 SettingsActivity（控件 id 原样保留在那里）。
    private TextView tvStatus, tvA11y, tvLog, tvAccount;
    private EditText etTask;
    private Button btnRun, btnMic;
    private View vTap;
    private ImageView ivScreen;
    private ScrollView svLog;
    private FrameLayout flScreen;

    /** 登录失效弹窗只提示一次（避免每次回前台都弹）。 */
    private boolean authPrompted;

    private final StringBuilder logBuf = new StringBuilder();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sInstance = this;

        // 全局沉浸式 Edge-to-Edge：彻底消除状态栏与底部手势导航的黑块闪烁
        android.view.Window window = getWindow();
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                | android.view.WindowManager.LayoutParams.FLAG_TRANSLUCENT_NAVIGATION);
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(android.graphics.Color.TRANSPARENT);
        window.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }

        watchUi = isWatchUi();
        setContentView(watchUi ? R.layout.activity_main_watch : R.layout.activity_main);

        // 启动即与同步服务对账一次：账号令牌鉴权（Bearer + X-Device-Id），增量上行 + 配置下发。
        // 未登录时直接跳过；内部走单线程 executor，不阻塞首帧；服务器不可达时静默失败、下次启动重试。
        SyncClient.syncNow(this);
        // 账号令牌失效（401）由同步/账号请求在后台判定，这里接收回调在 UI 上引导重新登录
        SyncClient.setAuthInvalidListener(() -> {
            refreshAccount();
            if (!authPrompted) promptRelogin();
        });
        View root = find(R.id.rootLayout);
        if (root != null) {
            final boolean isWatch = watchUi;
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                int top = insets.getSystemWindowInsetTop();
                int bottom = insets.getSystemWindowInsetBottom();
                if (isWatch) {
                    // 手表：水平安全边距由布局（@dimen/watch_edge，圆形/方形分档）控制，
                    // 此处只补系统栏，避免覆盖圆形屏的内接方形边距
                    v.setPadding(v.getPaddingLeft(), top, v.getPaddingRight(), bottom);
                } else {
                    v.setPadding(dp(16), top + dp(4), dp(16), bottom + dp(8));
                }
                return insets.consumeSystemWindowInsets();
            });
        }

        prefs = new Prefs(this);
        engine = AgentEngine.get(this);
        engine.setListener(this);

        bind();
        loadCfg();
        wire();
        handleIntent(getIntent());
        KeepAliveService.start(this);
        // SPEC-UX-001 §5（权限渐进申请）：启动期**不**再一次性索要录音 + 通知权限。
        // 录音权限在第一次点「语音」时申请（见 toggleMic），通知权限在第一次真正
        // 跑任务时申请（见 ensureNotifyPermission）——用户先明白要做什么，再被问权限。

        // 功能启用级弹窗（报告 §5.1.1：首次打开时告知操作与权限使用场景）
        if (!prefs.sp.getBoolean("intro_shown", false)) {
            prefs.sp.edit().putBoolean("intro_shown", true).apply();
            new android.app.AlertDialog.Builder(this)
                    .setTitle("Agent 操作手机说明")
                    .setMessage("本助手通过无障碍服务读取屏幕并模拟点击来替你操作手机。开始前，先说清三件事：\n\n"
                            + "【数据去哪】屏幕内容与截图会发送给你配置的模型服务（登录账号时走账号额度网关），"
                            + "用于决定下一步点哪里；本机不额外上传其它数据。\n\n"
                            + "【谁在控制】任务期间由助手操作手机；你随时可以夺回控制权——\n"
                            + "· 屏幕边缘的粉色呼吸光晕 = 助手正在操作，光晕熄灭 = 已暂停\n"
                            + "· 点顶部胶囊可接管、补充指令或停止\n\n"
                            + "【怎么停】点胶囊里的「停止」立即结束；点「接管」则暂停等你操作，"
                            + "处理完点「交还 AI」继续。\n\n"
                            + "支付、协议、验证码等敏感环节会强制暂停，由你手动完成；"
                            + "任务结束后可在「历史 / 授权 / 记忆」中回查与撤回。")
                    .setPositiveButton("知道了", null)
                    .show();
        }

        // 未登录引导：启动后直接打开登录页（不强制——返回键可回到主页继续本机使用）。
        // 手表形态同样只做引导，登录页自带圆形屏安全边距。
        // 已登录：带着有效会话启动局域网直连服务（密钥在登录时已派生并落盘）。
        if (!prefs.loggedIn()) {
            ui.postDelayed(() -> {
                if (!isFinishing() && !prefs.loggedIn()) {
                    startActivity(new Intent(MainActivity.this, LoginActivity.class));
                }
            }, 400);
        } else {
            LanSync.start(this);   // 已运行 / 未派生密钥 / 局域网关闭时内部安全跳过，重复调用无副作用
            RemoteCommand.startPolling(this);   // 跨设备指令轮询（未登录时内部跳过，重复调用无副作用）
            if (prefs.accountInvalid() && !authPrompted) {
                ui.postDelayed(this::promptRelogin, 400);
            }
        }
    }

    private void bind() {
        // 手机/手表共有的核心控件：用 find（缺控件时占位不崩）
        tvStatus = findText(R.id.tvStatus);
        tvA11y = findText(R.id.tvA11y);
        etTask = findEdit(R.id.etTask);
        btnRun = findButton(R.id.btnRun);
        btnMic = findButton(R.id.btnMic);
        // 手表主页没有这一行（账号状态在 WatchSettingsActivity 里）——
        // 过去这里用 find() 装 TextView 会 ClassCastException 崩溃
        tvAccount = findText(R.id.tvAccount);
        // 仅手机布局存在的控件：直接 findViewById（手表极简主页无这些控件，保持 null 并由使用点判空）
        tvLog = findViewById(R.id.tvLog);
        vTap = findViewById(R.id.vTap);
        ivScreen = findViewById(R.id.ivScreen);
        svLog = findViewById(R.id.svLog);
        flScreen = findViewById(R.id.flScreen);
    }

    /** 空安全的 findViewById：布局形态差异（手机/手表/精简布局）缺控件时返回不可见占位，绝不 NPE。 */
    @SuppressWarnings("unchecked")
    /**
     * 空安全的 findViewById：布局形态差异（手机/手表/精简布局）缺控件时返回占位。
     *
     * ⚠️ **不要用它给有具体类型的字段赋值**（如 `TextView t = find(...)`）。
     * 泛型签名 `<T extends View> T` 只是让编译器闭嘴，运行时并不做类型检查；
     * 缺控件时它返回的是裸 View，调用方那侧编译器插入的强转会抛
     * ClassCastException —— 手表布局没有 tvAccount，就直接崩在 bind() 里。
     * 有类型的场景请用 findText / findEdit / findButton（占位与调用方同类型）。
     */
    private <T extends View> T find(int id) {
        View v = findViewById(id);
        if (v == null) v = new View(this);
        return (T) v;
    }

    /** 缺控件时返回不可见占位 TextView：可安全 setText / setTextColor / setOnClickListener。 */
    private TextView findText(int id) {
        View v = findViewById(id);
        if (v instanceof TextView) return (TextView) v;
        TextView ph = new TextView(this);
        ph.setVisibility(View.GONE);
        return ph;
    }

    /** 缺控件时返回不可见占位 EditText。 */
    private EditText findEdit(int id) {
        View v = findViewById(id);
        if (v instanceof EditText) return (EditText) v;
        EditText ph = new EditText(this);
        ph.setVisibility(View.GONE);
        return ph;
    }

    /** 缺控件时返回不可见占位 Button。 */
    private Button findButton(int id) {
        View v = findViewById(id);
        if (v instanceof Button) return (Button) v;
        Button ph = new Button(this);
        ph.setVisibility(View.GONE);
        return ph;
    }

    /**
     * 主页不再承载任何配置项：模型/接口参数在 SettingsActivity，
     * 这里只刷新账号状态行（未登录 / 已登录 / 令牌失效）。
     */
    private void loadCfg() {
        refreshAccount();
    }

    /** 账号状态行：未登录或令牌失效时点按进登录页；其余情况点按进设置页看额度。 */
    private void refreshAccount() {
        if (tvAccount == null) return;
        if (!prefs.loggedIn()) {
            tvAccount.setText("账号：未登录 · 点此登录（未登录时历史与记忆只存本机）");
            tvAccount.setTextColor(0xFFFCD34D);
            return;
        }
        if (prefs.accountInvalid()) {
            tvAccount.setText("账号：" + prefs.accountName() + " · ⚠ 登录已失效，点此重新登录");
            tvAccount.setTextColor(0xFFF87171);
            authPrompted = false;   // 允许再次提示
            return;
        }
        long left = Math.max(0, prefs.accountQuota() - prefs.accountQuotaUsed());
        tvAccount.setText("账号：" + prefs.accountName() + " · 剩余 " + left + " units · 点此查看额度与设备");
        tvAccount.setTextColor(0xFF94A3B8);
    }

    private void wire() {
        // ---- 手表极简主页：只连 设置入口 / 任务输入 / 语音 / 运行 / 无障碍引导 ----
        // 历史、记忆、省电、授权、模型配置、设备形态均在 WatchSettingsActivity；
        // 日志与截图手表端不提供。样式沿用原设计语言（顶栏/卡片/按钮不变）。
        if (watchUi) {
            find(R.id.btnConfig).setOnClickListener(v ->
                    startActivity(new android.content.Intent(this, WatchSettingsActivity.class)));
            btnRun.setOnClickListener(v -> onRunClicked());
            btnMic.setOnClickListener(v -> toggleMic());
            find(R.id.btnA11yBanner).setOnClickListener(v -> openA11ySettingsDirectly());
            find(R.id.btnA11y).setOnClickListener(v -> openA11ySettingsDirectly());
            tvA11y.setOnClickListener(v -> openA11ySettingsDirectly());
            return;
        }

        // 设置改为**独立二级页面**：配置项（模型/接口/保活/设备/Token/账号额度）全部收进
        // SettingsActivity，主页不再内嵌配置面板，也就没有「返回」态需要维护。
        find(R.id.btnConfig).setOnClickListener(v -> openSettings());

        // 账号状态行：未登录/失效 → 登录页；已登录 → 设置页（额度与设备）
        tvAccount.setOnClickListener(v -> {
            if (!prefs.loggedIn() || prefs.accountInvalid()) {
                startActivity(new Intent(this, LoginActivity.class));
            } else {
                openSettings();
            }
        });

        find(R.id.btnA11yBanner).setOnClickListener(v -> openA11ySettingsDirectly());

        // 管理入口（豆包规格事后层：可回查、可撤回、可清除）
        find(R.id.btnHistory).setOnClickListener(v -> showHistory());
        find(R.id.btnAuth).setOnClickListener(v -> showAuthMgr());
        find(R.id.btnEco).setOnClickListener(v -> showEco());
        find(R.id.btnMemory).setOnClickListener(v -> showMemory());


        btnRun.setOnClickListener(v -> onRunClicked());

        btnMic.setOnClickListener(v -> toggleMic());

    }

    private AgentA11yService svc() {
        AgentA11yService s = AgentA11yService.get();
        if (s == null) onLog("✗ 无障碍服务未开启");
        return s;
    }


    // ---- 语音 ----

    /** 运行/停止（手机与手表极简主页共用）。 */
    private void onRunClicked() {
        if (engine.isRunning()) {
            engine.stop();
            return;
        }
        String task = etTask.getText().toString().trim();
        if (task.isEmpty()) { toast("请输入任务"); return; }
        // 记忆写入显式通道（报告 §7.1）：「记一下…」直接写入全局记忆，不进入执行循环
        if (task.startsWith("记一下")) {
            String mem = task.substring(3).trim();
            if (mem.isEmpty()) { toast("「记一下」后面跟上要记住的内容"); return; }
            MemoryStore.add(this, mem);
            onLog("已写入记忆：" + mem + "（之后任务会静默使用）");
            etTask.setText("");
            return;
        }
        if (!hasUsableCredential()) {
            // SPEC-UX-001 §3：无凭据时**禁止**向模型发请求。此前会直接启动任务、
            // 打一次注定失败的请求，再报「请检查 API 配置」——用户既看不懂也不知道
            // 下一步该做什么（手册 §16.2 P0-1）。改为前置拦截 + 一张可操作的解决卡。
            showCredentialCard();
            return;
        }
        if (AgentA11yService.get() == null) {
            // 服务可能刚被系统重绑（例：刚从无障碍设置页返回）——短暂等待重试，
            // 避免带着空感知启动任务（10 轮检查实测：会让模型看到「未开启」提示而误判失败）
            toast("无障碍服务正在连接…");
            waitA11yThenRun(task, 0);
            return;
        }
        startTask(task);
    }

    /**
     * 是否有可用的「大脑」配置？三条合法路径（手册 §16.5 第 3 步）：
     *
     * <ol>
     *   <li><b>账号登录</b>：用账号额度，推荐路径，不需要用户懂任何云服务概念；</li>
     *   <li><b>自带 Key</b>：高级路径，用户自己填了直连配置；</li>
     *   <li>未登录但已填 Key：同样可用（离线场景）。</li>
     * </ol>
     *
     * <p>令牌被判失效（401）也算不可用——继续跑只会得到一串 401。
     */
    private boolean hasUsableCredential() {
        if (!prefs.apiKey().isEmpty()) return true;          // 自带 Key（含高级路径）
        if (prefs.useOwnKey()) return false;                 // 明确选择自带 Key 但没填
        return prefs.loggedIn() && !prefs.accountInvalid();  // 账号额度路径
    }

    /**
     * 通知权限延迟到**首次真正跑任务**时申请（SPEC-UX-001 §5）。
     *
     * <p>它只影响前台服务进度通知是否可见：不授予任务照样能跑，所以没必要在
     * 启动瞬间抛给用户一个「为什么要通知」的疑问。只问一次，拒绝后不再纠缠。
     */
    private void ensureNotifyPermission() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == android.content.pm.PackageManager.PERMISSION_GRANTED) return;
        if (prefs.sp.getBoolean("notify_asked", false)) return;
        prefs.sp.edit().putBoolean("notify_asked", true).apply();
        requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 42);
    }

    /** 无凭据时的解决卡：人话解释 + 唯一主按钮 + 一条备选路径（SPEC-UX-001 §4）。 */
    private void showCredentialCard() {
        String msg = "助手需要一个「大脑」才能理解你的指令，现在还差最后一步：\n\n"
                + "· 登录账号 —— 直接使用账号额度，推荐，不需要任何配置\n"
                + "· 已经有自己的配置 —— 进入设置填写后即可使用\n\n"
                + "两种方式都只在你的设备与本机之间使用，随时可以在设置里更换或退出登录。";
        new android.app.AlertDialog.Builder(this)
                .setTitle("还差一步就能开始")
                .setMessage(msg)
                .setPositiveButton("登录 / 领取额度", (d, w) ->
                        startActivity(new Intent(this, LoginActivity.class)))
                .setNeutralButton("打开设置", (d, w) -> openSettings())
                .setNegativeButton("稍后", null)
                .show();
    }

    /**
     * 统一任务启动出口。
     * 1) 刷新无障碍状态（清掉可能残留的「未开启」横幅——否则横幅进入引擎首帧截图会误导模型）；
     * 2) 网络预检 + WiFi 高性能锁（手表长时间运行会被省电策略断网，实测出现
     *    「Unable to resolve host dashscope.aliyuncs.com」导致任务失败）；
     * 3) 启动引擎；手表上随即把本界面退到后台，避免自身 UI 遮挡首帧。
     */
    private void startTask(String task) {
        refreshA11y();
        if (!networkReady()) {
            toast("网络不可用：请检查手表 WiFi（省电模式下会被关闭）");
            return;
        }
        // 走到这里说明真要跑任务了：此时才解释并申请通知权限（SPEC-UX-001 §5）
        ensureNotifyPermission();
        acquireWifiLock();
        engine.start(task);
        toast("任务已启动，可上滑划出，灵动岛将实时显示进度");
        if (watchUi) {
            ui.postDelayed(() -> moveTaskToBack(true), 800);
        }
    }

    /** 是否有可用网络（INTERNET 能力）。 */
    private boolean networkReady() {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return true;
            android.net.Network n = cm.getActiveNetwork();
            if (n == null) return false;
            android.net.NetworkCapabilities cap = cm.getNetworkCapabilities(n);
            return cap != null && cap.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Throwable t) {
            return true;   // 检测异常时不阻塞任务
        }
    }

    // ---- 任务期间 WiFi 高性能锁（防省电策略断网） ----
    private android.net.wifi.WifiManager.WifiLock wifiLock;

    private void acquireWifiLock() {
        try {
            android.net.wifi.WifiManager wm =
                    (android.net.wifi.WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
            if (wm == null) return;
            if (wifiLock == null) {
                wifiLock = wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                        "agentlite:task");
                wifiLock.setReferenceCounted(false);
            }
            if (!wifiLock.isHeld()) wifiLock.acquire();
        } catch (Throwable ignored) { }
    }

    private void releaseWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        } catch (Throwable ignored) { }
    }

    /**
     * 等待无障碍服务就绪后再启动任务（最多 6 秒）。
     * 系统在无障碍设置页往返 / 进程回收后会短暂解绑再重绑服务，立即启动会拿到空感知。
     */
    private void waitA11yThenRun(String task, int tries) {
        if (AgentA11yService.get() != null) {
            startTask(task);
            return;
        }
        if (tries >= 6) {
            toast("无障碍服务未连接：请在设置中重新开启");
            refreshA11y();
            return;
        }
        ui.postDelayed(() -> waitA11yThenRun(task, tries + 1), 1000);
    }

    private void toggleMic() {
        if (recorder != null && recorder.isRecording()) {
            byte[] wav = recorder.stopToWav();
            btnMic.setText("语音");
            onLog("识别中…");
            new Thread(() -> {
                try {
                    LlmClient c = new LlmClient(prefs.baseUrl(), prefs.apiKey(), prefs.model());
                    String text = c.transcribe(wav, prefs.asrModel(), prefs.asrBaseUrl(), prefs.asrApiKey());
                    if (text.isEmpty()) { onLog("识别结果为空"); return; }
                    onLog("[语音输入] " + text);
                    ui.post(() -> {
                        etTask.setText(text);
                        btnRun.performClick();
                    });
                } catch (Exception e) {
                    onLog("语音识别失败：" + e.getMessage() + asrHint(e));
                }
            }, "asr").start();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
            return;
        }
        recorder = new VoiceRecorder();
        final VoiceRecorder r = recorder;
        // 30 秒上限到点自动收尾：与用户主动点「结束」走同一条路径
        r.setOnAutoStop(() -> ui.post(() -> {
            if (recorder != r) return;      // 已被其它路径结束
            onLog("录音已达 30 秒上限，自动结束");
            toggleMic();
        }));
        if (recorder.start()) {
            btnMic.setText("■");
            onLog("录音中…再点一次结束");
        } else {
            onLog("✗ 无法启动录音");
        }
    }

    /**
     * 把 ASR 异常翻译成**能照着做**的提示。
     *
     * <p>为什么需要：语音失败的原始异常对用户毫无指导意义，而下面这几类恰好覆盖了实测遇到的
     * 全部情况——2026-09-18 排查「语音又不能输入」时，用户在手机上看到的只有一句
     * 「语音识别失败」，既不知道是 Mac 没开机、服务没跑，还是被系统拦了明文。
     */
    private static String asrHint(Exception e) {
        String m = e.getMessage() == null ? "" : e.getMessage();
        if (m.contains("Cleartext")) {
            return "（该端点被系统拦截明文 HTTP：局域网服务应使用 http 且 App 已放开明文配置）";
        }
        if (m.contains("Connection refused")) {
            return "（ASR 服务没在跑：在 Mac 上执行 sh tools/asr_start.sh start）";
        }
        if (m.contains("ETIMEDOUT") || m.contains("timeout") || m.contains("Failed to connect")) {
            return "（连不上 ASR 服务：确认 Mac 已开机、手机与它同一 Wi-Fi，且端点里的 IP 是 Mac 当前地址）";
        }
        if (m.contains("404")) {
            return "（端点路径不对：应形如 http://<Mac的IP>:18888/v1）";
        }
        if (m.contains("UnknownHost")) {
            return "（解析不了端点主机名：检查端点里填的 IP 是否正确）";
        }
        return "";
    }

    // ---- AgentEngine.Listener（引擎线程回调 → UI 线程） ----

    @Override
    public void onLog(String line) {
        android.util.Log.i("AgentUI", line);
        if (watchUi) return;   // 手表端不显示日志区（adb logcat / engine.log 可查）
        String ts = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        ui.post(() -> {
            logBuf.append('[').append(ts).append("] ").append(line).append('\n');
            if (logBuf.length() > 12000) logBuf.delete(0, logBuf.length() - 9000);
            tvLog.setText(logBuf);
            svLog.post(() -> svLog.fullScroll(View.FOCUS_DOWN));
        });
    }

    @Override
    public void onStatus(boolean running, int step) {
        if (!running) releaseWifiLock();   // 任务结束：释放 WiFi 高性能锁
        ui.post(() -> {
            tvStatus.setText(running ? "运行中 · 第 " + step + " 步" : "就绪");
            if (watchUi) return;   // 手表极简主页：只更新状态文字
            tvStatus.setTextColor(running ? 0xFF34D399 : 0xFF94A3B8);
            tvStatus.setBackgroundResource(running ? R.drawable.pill_green : R.drawable.pill_gray);
            btnRun.setText(running ? "停止" : "运行");
        });
    }

    @Override
    public void onScreen(Bitmap bmp) {
        if (watchUi) return;   // 手表端不显示屏幕预览（省内存/流量）
        ui.post(() -> { lastBmp = bmp; ivScreen.setImageBitmap(bmp); });
    }

    @Override
    public void onTap(int x, int y) {
        if (watchUi) return;   // 手表端无预览画布，跳过点击标记
        ui.post(() -> {
            if (lastBmp == null) return;
            flScreen.post(() -> {
                int vw = flScreen.getWidth(), vh = flScreen.getHeight();
                float scale = Math.min((float) vw / lastBmp.getWidth(), (float) vh / lastBmp.getHeight());
                float dw = lastBmp.getWidth() * scale, dh = lastBmp.getHeight() * scale;
                float offX = (vw - dw) / 2f, offY = (vh - dh) / 2f;
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) vTap.getLayoutParams();
                lp.leftMargin = Math.round(offX + x * scale - lp.width / 2f);
                lp.topMargin = Math.round(offY + y * scale - lp.height / 2f);
                vTap.setLayoutParams(lp);
                vTap.setVisibility(View.VISIBLE);
                vTap.postDelayed(() -> vTap.setVisibility(View.GONE), 2500);
            });
        });
    }

    @Override
    public void onAsk(String question) {
        pendingAsk = question;
        fireAskNotification(question);   // 全屏通知：其他 App 界面也会弹
        ui.post(this::showAskDialogIfNeeded);
    }

    /** 模型提问的待回答状态。 */
    private String pendingAsk;
    private boolean askDialogShowing;

    private void showAskDialogIfNeeded() {
        if (pendingAsk == null || askDialogShowing) return;
        askDialogShowing = true;
        final android.widget.EditText input = new android.widget.EditText(this);
        input.setHint("输入回答…");
        new android.app.AlertDialog.Builder(this)
                .setTitle("Agent 提问")
                .setMessage(pendingAsk)
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("回答", (d, w) -> {
                    engine.answerAsk(input.getText().toString().trim());
                    pendingAsk = null; askDialogShowing = false;
                    cancelAskNotification();
                })
                .setNegativeButton("跳过", (d, w) -> {
                    engine.answerAsk("");
                    pendingAsk = null; askDialogShowing = false;
                    cancelAskNotification();
                })
                .show();
    }

    private static final int ASK_NOTIF_ID = 9021;

    private void fireAskNotification(String question) {
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm == null) return;
        String ch = "agent_ask";
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new android.app.NotificationChannel(ch, "Agent 提问",
                    android.app.NotificationManager.IMPORTANCE_HIGH));
        }
        android.content.Intent it = new android.content.Intent(this, MainActivity.class)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                        | android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP);
        android.app.PendingIntent pi = android.app.PendingIntent.getActivity(this, 0, it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification n = new android.app.Notification.Builder(this, ch)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Agent 需要你的回答")
                .setContentText(question)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)   // 来电级弹出（零 root）
                .setAutoCancel(true)
                .build();
        nm.notify(ASK_NOTIF_ID, n);
    }

    private void cancelAskNotification() {
        android.app.NotificationManager nm = getSystemService(android.app.NotificationManager.class);
        if (nm != null) nm.cancel(ASK_NOTIF_ID);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 前台自检：回前台时纠正"设置里已开启但服务未连接"的静默掉线（李跳跳式保活的一环）
        A11yGuard.check(this, "前台自检");
        refreshA11y();
        refreshAccount();          // 账号状态行（未登录 / 令牌失效 / 剩余额度）
        // 从登录页返回后可能刚恢复登录：清掉失效标记的提示计时
        if (!prefs.accountInvalid()) authPrompted = false;
        if (prefs.loggedIn() && prefs.accountInvalid() && !authPrompted) promptRelogin();
    }

    @Override
    protected void onStart() {
        super.onStart();
        // 订阅无障碍恢复信号：服务重绑是异步的，onResume 那一刻可能还没连上，
        // 若不重画，横幅会一直停在「未开启」并进入引擎首帧截图（误导模型）。
        A11yGuard.setObserver(a11yObserver);
    }

    @Override
    protected void onStop() {
        super.onStop();
        A11yGuard.setObserver(null);   // 必须注销：静态引用持有 Activity 会泄漏
    }

    /** 无障碍状态变化回调（主线程）。仅在"已连接"时重画——掉线本身已有 onResume 路径覆盖。 */
    private final A11yGuard.Observer a11yObserver = (connected) -> {
        if (connected && !isFinishing()) refreshA11y();
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 注销同步失效回调，避免持有已销毁界面
        SyncClient.setAuthInvalidListener(null);
    }

    /** 登录令牌失效的统一提示（401 由 SyncClient / AccountClient 判定后置位）。 */
    private void promptRelogin() {
        if (isFinishing()) return;
        authPrompted = true;
        new android.app.AlertDialog.Builder(this)
                .setTitle("登录已失效")
                .setMessage("登录状态已失效，需要重新登录一次。\n"
                        + "重新登录后任务历史与记忆会继续同步；"
                        + "同一账号的多台设备（手机 / 手表）现在可同时在线，不会互相挤掉。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("重新登录", (d, w) ->
                        startActivity(new Intent(this, LoginActivity.class)))
                .show();
    }

    /** 二级设置页（模型 / 接口 / 账号额度 / 同步 / 保活）。 */
    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override
    protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        handleIntent(intent);
        ui.post(this::showAskDialogIfNeeded);
    }

    /**
     * 进程内任务传递（AssistActivity → MainActivity）。
     *
     * <p><b>为什么不用 Intent extra</b>：本 Activity 是 `exported="true"` 的启动器入口，
     * 任何已安装应用都能构造 `--es task "…" --ez auto_run true` 让无障碍代理**自动执行**
     * 一段它指定的任务（安全审查 2026-09-15 P0-1：confused deputy / 本地提权）。
     * 改成进程内静态通道后，外部 Intent 里即使带 task/auto_run 也不会被采纳。
     */
    private static volatile String sPendingTask;

    /** 仅供进程内组件（AssistActivity）调用：登记待执行任务。 */
    static void setPendingTask(String task) {
        sPendingTask = task;
    }

    private void handleIntent(android.content.Intent it) {
        if (it == null) return;
        // 复用已在前台的实例（singleTask）时不会走 onResume：这里补一次状态刷新，
        // 否则从系统无障碍页返回 / adb 唤起的场景会显示过期的服务状态（横幅滞留）。
        refreshA11y();
        if (it.getBooleanExtra("a11y_repair", false)) {
            // 来自掉线通知：先尝试自动修复；仍不健康则直达无障碍设置页由用户一键恢复
            if (!A11yGuard.check(this, "通知修复")) {
                ui.postDelayed(() -> A11yLauncher.open(this), 600);
            }
        }
        // 任务只从**进程内通道**取；Intent 里的 task/auto_run 一律忽略（P0-1）。
        // 外部来源即使伪造 extra，也只能启动界面，无法驱动引擎。
        String task = sPendingTask;
        sPendingTask = null;
        if (task != null && !task.trim().isEmpty()) {
            etTask.setText(task.trim());
            ui.postDelayed(() -> {
                if (btnRun != null && !engine.isRunning()) btnRun.performClick();
            }, 300);
        }
    }

    private void refreshA11y() {
        boolean a11yOn = AgentA11yService.get() != null;
        // root 若可用则静默加速 + 自动补开无障碍（Lite 无 root 模式 UI，默认 auto）
        boolean root = RootShell.available(this);
        if (root && !a11yOn) {
            RootShell.ensureA11y(this);
            a11yOn = AgentA11yService.get() != null;
        }
        renderA11y(a11yOn, root);
    }

    /**
     * 只负责画状态，不触发修复。
     *
     * <p>拆出来的原因：服务重绑是**异步**的——`onResume` 里判定时它可能还没连上，
     * 等连上时横幅早已画完，于是界面会一直挂着「无障碍未开启」，
     * 而这一帧截图会被引擎当成首帧感知输入、**误导模型以为它看不见屏幕**
     * （原注释已点明这个风险，但当时没有恢复信号可用）。
     * 现在由 {@link A11yGuard} 在真实恢复时回调 {@link #onStart} 注册的监听器重画。
     */
    private void renderA11y(boolean a11yOn, boolean root) {
        // 状态文案统一由 A11yLauncher 判定：区分「未开启」与「已启用但未连接（被强杀/回收）」
        String hint = a11yOn ? null : A11yLauncher.statusHint(this);
        if (watchUi) {
            // 手表极简主页：tvA11y 是提示行（点击直达系统设置），正常时隐藏
            tvA11y.setText(hint == null ? "" : hint);
            tvA11y.setVisibility(hint == null ? View.GONE : View.VISIBLE);
            return;
        }
        tvA11y.setText(a11yOn
                ? (root ? "无障碍已开启 (Root)" : "无障碍已开启")
                : hint);
        tvA11y.setTextColor(a11yOn ? 0xFF3FB950 : 0xFFF85149);
        tvA11y.setBackgroundResource(a11yOn ? R.drawable.pill_green : R.drawable.pill_red);
        // 引导横幅：无障碍未开时置顶显示一键开启
        View banner = find(R.id.a11yBanner);
        if (banner != null) banner.setVisibility(a11yOn ? View.GONE : View.VISIBLE);
    }

    // ---- 事后管理（报告 §5.1.3：可回查 / 可撤回 / 可清除） ----

    /** 任务记录归档：完成/失败/终止三类终态统一可回查。 */
    private void showHistory() {
        List<TaskHistory.Record> rs = TaskHistory.list(this);
        StringBuilder b = new StringBuilder();
        if (rs.isEmpty()) b.append("暂无任务记录");
        for (TaskHistory.Record r : rs) b.append(r.line()).append("\n\n");
        new android.app.AlertDialog.Builder(this)
                .setTitle("任务记录")
                .setMessage(b.toString().trim())
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空", (d, w) -> {
                    TaskHistory.clear(this);
                    onLog("任务记录已清空");
                })
                .show();
    }

    /** 预授权管理：点按逐条撤回，可全部重置；撤回后再次发起会重走授权弹窗。 */
    private void showAuthMgr() {
        AuthStore store = new AuthStore(this);
        List<String> scopes = store.list();
        if (scopes.isEmpty()) {
            new android.app.AlertDialog.Builder(this)
                .setTitle("预授权管理")
                .setMessage("当前所有应用均已开放直接打开与操作。\n不再弹出授权阻断框。")
                .setPositiveButton("关闭", null)
                .show();
            return;
        }
        String[] items = new String[scopes.size()];
        for (int i = 0; i < scopes.size(); i++) {
            items[i] = scopes.get(i).replace("app|", "操作应用：") + "　[始终允许]";
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("预授权管理（点按撤回）")
                .setItems(items, (d, which) -> {
                    store.revoke(scopes.get(which));
                    onLog("已撤回授权：" + scopes.get(which));
                })
                .setNeutralButton("全部重置", (d, w) -> {
                    store.clearAll();
                    onLog("预授权已全部重置");
                })
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 生态清单：三档梯度（可全自动/可登录不可AI操作/受限·纯视觉尝试），点按循环切换。 */
    private void showEco() {
        EcoPolicy eco = new EcoPolicy(this);
        List<EcoPolicy.Entry> es = eco.entries();
        String[] items = new String[es.size()];
        for (int i = 0; i < es.size(); i++) {
            EcoPolicy.Entry e = es.get(i);
            items[i] = e.name + "　[" + EcoPolicy.tierLabel(e.tier) + "]"
                    + (e.altName != null ? "　→ 替代：" + e.altName : "");
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("生态清单（点按切换档位）")
                .setItems(items, (d, which) -> {
                    EcoPolicy.Entry e = es.get(which);
                    e.tier = (e.tier + 1) % 3;
                    eco.save(es);
                    onLog("「" + e.name + "」已变更为 " + EcoPolicy.tierLabel(e.tier));
                })
                .setNeutralButton("恢复默认", (d, w) -> {
                    eco.reset();
                    onLog("生态清单已恢复默认");
                })
                .setPositiveButton("关闭", null)
                .show();
    }

    /** 记忆管理：点按删除单条，可清空；写入用「记一下…」，调用静默无披露。 */
    private void showMemory() {
        List<MemoryStore.Item> ms = MemoryStore.list(this);
        if (ms.isEmpty()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("全局记忆")
                    .setMessage("暂无记忆。\n在任务框输入「记一下 …」即可写入；记忆会在后续任务中被静默使用。")
                    .setPositiveButton("关闭", null)
                    .show();
            return;
        }
        String[] items = new String[ms.size()];
        for (int i = 0; i < ms.size(); i++) {
            items[i] = ms.get(i).time + "  " + ms.get(i).text;
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("全局记忆（点按删除）")
                .setItems(items, (d, which) -> {
                    MemoryStore.delete(this, which);
                    onLog("已删除记忆：" + ms.get(which).text);
                })
                .setNeutralButton("清空", (d, w) -> {
                    MemoryStore.clear(this);
                    onLog("记忆已清空");
                })
                .setPositiveButton("关闭", null)
                .show();
    }

    /**
     * 智能自动定位无障碍详情页（各 ROM 入口差异 → 统一走 A11yLauncher 的可靠降级链）。
     */
    private void openA11ySettingsDirectly() {
        A11yLauncher.open(this);
    }

    /** 申请忽略电池优化白名单（锁屏保活核心） */
    private void requestIgnoreBatteryOptimizations() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(android.content.Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                toast("已在电池优化白名单中，锁屏保活已激活");
                return;
            }
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(android.net.Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Throwable ex) {
                toast("请在系统设置 -> 电池优化中，将 Agent Lite 设为「不优化/允许后台活动」");
            }
        }
    }

    private int dp(int v) {
        return (int) android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
