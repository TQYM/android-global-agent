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
    private TextView tvLastMsg;   // 手机适老化主页：最近一条提示（大字常驻，代替一闪而过的 Toast）
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
        // 手机形态：浅色高对比的适老化主题（大字、大按钮）；手表保持原深色紧凑主题
        if (!watchUi) ElderUi.applyTheme(this);
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
                int systemBottom = insets.getSystemWindowInsetBottom();
                int imeBottom = 0;
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    imeBottom = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
                    systemBottom = Math.max(systemBottom,
                            insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom);
                }
                // adjustResize 只缩小视口；ScrollView 还需要 IME 底部 padding 才能把输入行
                // 滚到键盘上方，否则底部按钮仍可能落在键盘覆盖区。
                int bottom = Math.max(systemBottom, imeBottom);
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
        AsrRouter.warmUp(this);   // 预热离线转写模型，首次说话就不用等加载
        engine = AgentEngine.get(this);
        engine.setListener(this);

        bind();
        installImeScrollAssist();
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
            ElderUi.show(new android.app.AlertDialog.Builder(this)
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
                            + "任务结束后可在「做过的事 / 授权管理 / 记住的事」中回查与撤回。")
                    .setPositiveButton("知道了", null));
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
        tvLastMsg = findViewById(R.id.tvLastMsg);
    }

    /** 键盘出现时把任务输入行滚入可见区域，避免 ScrollView 的底部控件被 IME 覆盖。 */
    private void installImeScrollAssist() {
        if (watchUi || etTask == null) return;
        final ScrollView root = findViewById(R.id.rootLayout);
        if (root == null) return;
        etTask.setOnFocusChangeListener((v, hasFocus) -> {
            if (!hasFocus) return;
            v.postDelayed(() -> root.requestChildRectangleOnScreen(v,
                    new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight()), true), 180);
        });
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
            tvAccount.setText("账号：还没登录 · 点这里登录\n（不登录也能用，记录只存在这台手机上）");
            tvAccount.setTextColor(getColor(R.color.elder_warn_text));
            return;
        }
        if (prefs.accountInvalid()) {
            tvAccount.setText("账号：" + prefs.accountName() + "\n登录已过期，点这里重新登录");
            tvAccount.setTextColor(getColor(R.color.elder_err_text));
            authPrompted = false;   // 允许再次提示
            return;
        }
        // 服务端 quota 语义就是当前剩余量；quota_used 是累计用量，不能再次相减。
        long left = Math.max(0, prefs.accountQuota());
        tvAccount.setText("账号：" + prefs.accountName() + "\n剩余额度 " + left + " · 点这里查看额度与设备");
        tvAccount.setTextColor(getColor(R.color.elder_text));
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
        // 状态行写着「点此开启」：手机上同样可点，直达系统无障碍页
        tvA11y.setOnClickListener(v -> {
            if (AgentA11yService.get() == null) openA11ySettingsDirectly();
        });

        // 管理入口（豆包规格事后层：可回查、可撤回、可清除）
        find(R.id.btnHistory).setOnClickListener(v -> showHistory());
        find(R.id.btnAuth).setOnClickListener(v -> showAuthMgr());
        find(R.id.btnEco).setOnClickListener(v -> showEco());
        find(R.id.btnMemory).setOnClickListener(v -> showMemory());


        btnRun.setOnClickListener(v -> onRunClicked());

        btnMic.setOnClickListener(v -> toggleMic());

        // 「试试这样说」：只把例句填进输入框，由用户自己点「开始」——不替用户做决定
        fillExample(R.id.btnEx1, "把声音调大一点");
        fillExample(R.id.btnEx2, "把屏幕调亮一点");
        fillExample(R.id.btnEx3, "打开微信");
        fillExample(R.id.btnEx4, "记一下：");   // 显式记忆通道（见 onRunClicked「记一下」分支）
    }

    /** 例句按钮：填入输入框并把光标放到末尾；「记一下：」需要接着输入，顺手弹出键盘。 */
    private void fillExample(int id, String text) {
        find(id).setOnClickListener(v -> {
            etTask.setText(text);
            etTask.setSelection(text.length());
            if (text.endsWith("：")) {
                etTask.requestFocus();
                android.view.inputmethod.InputMethodManager imm =
                        (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                if (imm != null) imm.showSoftInput(etTask, 0);
                say("请接着输入要我记住的事，例如：记一下：我住在3号楼2单元。然后点「开始」。");
            } else {
                say("已填好：「" + text + "」。确认没问题，就点下面的「开始」。");
            }
        });
    }

    /**
     * 面向用户的即时反馈：写日志的同时，在手机主页的提示框里用大字显示。
     *
     * <p>改版前这类反馈（「已写入记忆」「录音中…」「已撤回授权」）只进隐藏的日志区，
     * 用户点了按钮看不到任何变化——对老年用户来说等于「没反应」，会反复点。
     * 引擎自己的技术日志仍只走 {@link #onLog}，不在这里展示（守则 T-4）。
     */
    private void say(String line) {
        onLog(line);
        if (watchUi || tvLastMsg == null) return;
        ui.post(() -> {
            tvLastMsg.setText(line);
            tvLastMsg.setVisibility(View.VISIBLE);
        });
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
        if (task.isEmpty()) { toast(watchUi ? "请输入任务" : "请先点「我要说话」说一句，或者打字告诉我要做什么"); return; }
        // 记忆写入显式通道（报告 §7.1）：「记一下…」直接写入全局记忆，不进入执行循环
        if (task.startsWith("记一下")) {
            String mem = task.substring(3).trim();
            if (mem.startsWith("：") || mem.startsWith(":")) mem = mem.substring(1).trim();
            if (mem.isEmpty()) { toast("「记一下」后面跟上要记住的内容"); return; }
            MemoryStore.add(this, mem);
            say("好的，我记住了：" + mem + "（以后做事时会用上）");
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

    /** 与请求工厂共用凭据解析；本地配置齐备不代表远端已鉴权或有余额。 */
    private boolean hasUsableCredential() {
        return prefs.credentialRoute(prefs.model()).isUsable();
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
        CredentialRoute route = prefs.credentialRoute(prefs.model());
        boolean needsLogin = route.reason == CredentialRoute.Reason.LOGIN_REQUIRED
                || route.reason == CredentialRoute.Reason.LOGIN_EXPIRED;
        String msg = route.userMessage() + "\n\n"
                + "账号额度由应用账号扣减，自己的配置由对应服务账号计费。"
                + "模型请求可能上传任务文字和截图，并非只在本机处理。"
                + "切换方式需要你主动选择，失败时不会自动换来源。";
        ElderUi.show(new android.app.AlertDialog.Builder(this)
                .setTitle("还差一步就能开始")
                .setMessage(msg)
                .setPositiveButton(needsLogin ? "登录账号" : "选择使用方式", (d, w) -> {
                    if (needsLogin) startActivity(new Intent(this, LoginActivity.class));
                    else CredentialRouteUi.showChooser(this, prefs, this::refreshAccount);
                })
                .setNeutralButton("打开设置", (d, w) -> openSettings())
                .setNegativeButton("稍后", null));
    }

    /**
     * 统一任务启动出口。
     * 1) 刷新无障碍状态（清掉可能残留的「未开启」横幅——否则横幅进入引擎首帧截图会误导模型）；
     * 2) 网络预检 + WiFi 高性能锁（手表长时间运行会被省电策略断网，实测出现
     *    「Unable to resolve host dashscope.aliyuncs.com」导致任务失败）；
     * 3) 启动引擎；手表上随即把本界面退到后台，避免自身 UI 遮挡首帧。
     */
    private void startTask(String task) {
        // 等待无障碍重连期间登录态可能已改变，再查一次；请求工厂仍独立守住最终出口。
        if (!hasUsableCredential()) {
            showCredentialCard();
            return;
        }
        refreshA11y();
        if (!networkReady()) {
            toast(watchUi ? "网络不可用：请检查手表 WiFi（省电模式下会被关闭）"
                    : "现在没有网络：请检查手机是否连上了无线网（WiFi）或打开了移动数据");
            return;
        }
        // 走到这里说明真要跑任务了：此时才解释并申请通知权限（SPEC-UX-001 §5）
        ensureNotifyPermission();
        acquireWifiLock();
        engine.start(task);
        if (watchUi) {
            toast("使用" + prefs.credentialRoute(prefs.model()).payerLabel()
                    + "，任务已启动；灵动岛将显示进度");
        } else {
            say("好的，我开始做了（使用" + prefs.credentialRoute(prefs.model()).payerLabel() + "）。\n"
                    + "屏幕边上亮起粉色光圈，就是我在操作；想停下来，回到这里点红色的「停止」。");
        }
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
            if (watchUi) {
                btnMic.setText("语音");
            } else {
                setMicLook(MIC_BUSY);
            }
            say("正在听懂您说的话，请稍等…");
            new Thread(() -> {
                try {
                    // 转写走统一入口：本地离线 whisper 优先，其次独立端点，最后主模型音频
                    String text = AsrRouter.transcribe(this, prefs, wav);
                    ui.post(() -> setMicLook(MIC_IDLE));
                    if (text.isEmpty()) { say("没听清楚，请靠近手机再说一次，或者直接打字"); return; }
                    say("我听到的是：「" + text + "」");
                    onLog("[语音输入] " + text);
                    ui.post(() -> {
                        etTask.setText(text);
                        btnRun.performClick();
                    });
                } catch (Exception e) {
                    ui.post(() -> setMicLook(MIC_IDLE));
                    // 技术原因只进日志；用户看到的是能照着做的一句话
                    onLog("语音识别失败：" + e.getMessage() + asrHint(e));
                    if (!watchUi) say("这次没听出来，请再点「我要说话」试一次，或者直接打字");
                }
            }, "asr").start();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (!watchUi) say("第一次用语音，手机会问是否允许使用麦克风，请点「允许」，然后再点一次「我要说话」");
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 2);
            return;
        }
        recorder = new VoiceRecorder();
        final VoiceRecorder r = recorder;
        // 30 秒上限到点自动收尾：与用户主动点「结束」走同一条路径
        r.setOnAutoStop(() -> ui.post(() -> {
            if (recorder != r) return;      // 已被其它路径结束
            say("已经说了 30 秒，我先停下来听一听");
            toggleMic();
        }));
        if (recorder.start()) {
            if (watchUi) {
                btnMic.setText("■");
            } else {
                setMicLook(MIC_REC);
            }
            say("我在听，请说…说完点「说完了」");
        } else {
            say("麦克风打不开，请检查是否允许了麦克风权限，也可以直接打字");
        }
    }

    private static final int MIC_IDLE = 0, MIC_REC = 1, MIC_BUSY = 2;

    /** 手机主页「我要说话」按钮的三种样子：待命（蓝）/ 正在听（红，点了就结束）/ 识别中（灰，不可点）。 */
    private void setMicLook(int state) {
        if (watchUi || btnMic == null) return;
        TextView hint = findViewById(R.id.tvMicHint);
        switch (state) {
            case MIC_REC:
                btnMic.setEnabled(true);
                btnMic.setText("说完了，点这里");
                btnMic.setBackgroundResource(R.drawable.btn_elder_danger);
                btnMic.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_elder_stop, 0, 0, 0);
                if (hint != null) hint.setText("正在听…最多 30 秒，说完点上面的红色按钮");
                break;
            case MIC_BUSY:
                btnMic.setEnabled(false);
                btnMic.setText("正在识别…");
                btnMic.setBackgroundResource(R.drawable.btn_elder_primary);
                if (hint != null) hint.setText("请稍等，马上就好");
                break;
            default:
                btnMic.setEnabled(true);
                btnMic.setText("我要说话");
                btnMic.setBackgroundResource(R.drawable.btn_elder_primary);
                btnMic.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_elder_mic, 0, 0, 0);
                if (hint != null) hint.setText("点一下开始说，说完再点一下，我就开始做");
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
            if (watchUi) {   // 手表极简主页：只更新状态文字
                tvStatus.setText(running ? "运行中 · 第 " + step + " 步" : "就绪");
                return;
            }
            taskRunning = running;
            tvStatus.setText(running ? "正在帮您操作…（第 " + step + " 步）" : idleStatusText());
            tvStatus.setTextColor(getColor(running ? R.color.elder_ok_text : R.color.elder_text));
            // 运行中：「开始」变成红色「停止」，同一个位置、同一个按钮，老人不用找
            btnRun.setText(running ? "停止" : "开始");
            btnRun.setBackgroundResource(running ? R.drawable.btn_elder_danger : R.drawable.btn_elder_primary);
            btnRun.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    running ? R.drawable.ic_elder_stop : R.drawable.ic_elder_play, 0, 0, 0);
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
        ElderUi.show(new android.app.AlertDialog.Builder(this)
                .setTitle("Agent 提问")
                .setMessage(pendingAsk)
                .setView(input)
                .setCancelable(false)
                .setPositiveButton("回答", (d, w) -> {
                    engine.answerAsk(input.getText().toString().trim());
                    pendingAsk = null; askDialogShowing = false;
                    cancelAskNotification();
                })
                .setNegativeButton("不执行", (d, w) -> {
                    engine.answerAsk("");
                    pendingAsk = null; askDialogShowing = false;
                    cancelAskNotification();
                }));
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
        ElderUi.show(new android.app.AlertDialog.Builder(this)
                .setTitle("登录已失效")
                .setMessage("登录状态已失效，需要重新登录一次。\n"
                        + "重新登录后任务历史与记忆会继续同步；"
                        + "同一账号的多台设备（手机 / 手表）现在可同时在线，不会互相挤掉。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("重新登录", (d, w) ->
                        startActivity(new Intent(this, LoginActivity.class))));
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
        a11yReady = a11yOn;
        tvA11y.setText(a11yOn
                ? (root ? "无障碍开关已打开（Root 加速可用），我可以帮您操作手机" : "无障碍开关已打开，我可以帮您操作手机")
                : hint);
        tvA11y.setTextColor(getColor(a11yOn ? R.color.elder_ok_text : R.color.elder_err_text));
        tvA11y.setBackgroundResource(a11yOn ? R.drawable.bg_elder_ok : R.drawable.bg_elder_err);
        if (!taskRunning) {
            tvStatus.setText(idleStatusText());
            tvStatus.setTextColor(getColor(R.color.elder_text));
        }
        // 引导横幅：无障碍未开时置顶显示一键开启
        View banner = find(R.id.a11yBanner);
        if (banner != null) banner.setVisibility(a11yOn ? View.GONE : View.VISIBLE);
    }

    /** 手机主页状态卡（不在运行时）：与无障碍状态一致，避免「准备好了」和「还差一步」同屏打架。 */
    private boolean a11yReady = true;
    private boolean taskRunning;

    private String idleStatusText() {
        return a11yReady ? "准备好了，请告诉我要做什么" : "还差一步：请先打开无障碍开关";
    }

    // ---- 事后管理（报告 §5.1.3：可回查 / 可撤回 / 可清除） ----

    /** 任务记录归档：完成/失败/终止三类终态统一可回查。 */
    private void showHistory() {
        List<TaskHistory.Record> rs = TaskHistory.list(this);
        StringBuilder b = new StringBuilder();
        if (rs.isEmpty()) b.append("暂无任务记录");
        for (TaskHistory.Record r : rs) b.append(r.line()).append("\n\n");
        ElderUi.show(new android.app.AlertDialog.Builder(this)
                .setTitle("做过的事")
                .setMessage(b.toString().trim())
                .setPositiveButton("关闭", null)
                .setNeutralButton("清空", rs.isEmpty() ? null : (d, w) -> confirm(
                        "清空全部记录？", "清空后以前做过的事就看不到了，不能恢复。", "清空", () -> {
                            TaskHistory.clear(this);
                            say("做过的事已全部清空");
                        })));
    }

    /** 预授权管理：点按逐条撤回，可全部重置；撤回后再次发起会重走授权弹窗。 */
    private void showAuthMgr() {
        AuthStore store = new AuthStore(this);
        List<String> scopes = store.list();
        if (scopes.isEmpty()) {
            ElderUi.show(new android.app.AlertDialog.Builder(this)
                .setTitle("授权管理")
                .setMessage("当前所有应用均已开放直接打开与操作。\n不再弹出授权阻断框。")
                .setPositiveButton("关闭", null));
            return;
        }
        String[] items = new String[scopes.size()];
        for (int i = 0; i < scopes.size(); i++) {
            items[i] = scopes.get(i).replace("app|", "操作应用：") + "　[始终允许]";
        }
        ElderUi.show(new android.app.AlertDialog.Builder(this)
                .setTitle("授权管理（点一项就收回）")
                .setAdapter(ElderUi.list(this, items), (d, which) -> {
                    store.revoke(scopes.get(which));
                    say("已收回授权：" + scopes.get(which).replace("app|", "") + "。下次操作它前会先问您");
                })
                .setNeutralButton("全部重置", (d, w) -> confirm(
                        "收回全部授权？", "以后每次操作这些应用前，都会先问您一次。", "全部重置", () -> {
                            store.clearAll();
                            say("已收回全部授权");
                        }))
                .setPositiveButton("关闭", null));
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
        ElderUi.show(new android.app.AlertDialog.Builder(this)
                .setTitle("应用限制（点一项换一档）")
                .setAdapter(ElderUi.list(this, items), (d, which) -> {
                    EcoPolicy.Entry e = es.get(which);
                    e.tier = (e.tier + 1) % 3;
                    eco.save(es);
                    say("「" + e.name + "」已改为：" + EcoPolicy.tierLabel(e.tier));
                    showEco();   // 重新打开，方便连续调整、立刻看到新档位
                })
                .setNeutralButton("恢复默认", (d, w) -> confirm(
                        "恢复默认设置？", "您改过的应用限制会全部改回出厂设置。", "恢复默认", () -> {
                            eco.reset();
                            say("应用限制已恢复默认");
                        }))
                .setPositiveButton("关闭", null));
    }

    /**
     * 记忆库：**操作记忆**（引擎自己走通过、可直接复现的执行路径）+ **用户记忆**
     * （「记一下…」写入的事实，注入提示词静默使用）。
     *
     * <p>两类记忆放在同一页，是因为对用户来说它们就是一件事——「你记住了我什么」。
     * 操作记忆排在最前，并带一个默认开启的总开关：关掉它即回到每次都重新规划。
     */
    private void showMemory() {
        final List<FlowPlan.Record> flows = FlowMemory.list(this);
        final List<MemoryStore.Item> facts = MemoryStore.list(this);
        final boolean on = new Prefs(this).flowMemory();

        final List<String> items = new java.util.ArrayList<>();
        final List<Runnable> acts = new java.util.ArrayList<>();
        items.add((on ? "☑ " : "☐ ") + "自动记录成功操作（默认开）——点按"
                + (on ? "关闭" : "开启"));
        acts.add(() -> {
            new Prefs(this).setFlowMemory(!on);
            say(!on ? "已打开：做成功的事我会记住步骤，下次同样的事做得更快"
                      : "已关闭：以前记住的还在，但不再自动记新的");
            showMemory();
        });
        for (int i = 0; i < flows.size(); i++) {
            final int idx = i;
            final FlowPlan.Record r = flows.get(i);
            items.add("操作记忆 · " + shorten(r.task, 14) + " · " + FlowMemory.label(r));
            acts.add(() -> {
                FlowMemory.delete(this, idx);
                say("已删除：" + shorten(r.task, 20));
                showMemory();
            });
        }
        for (int i = 0; i < facts.size(); i++) {
            final int idx = i;
            final MemoryStore.Item it = facts.get(i);
            items.add("用户记忆 · " + shorten(it.text, 20));
            acts.add(() -> {
                MemoryStore.delete(this, idx);
                say("已删除：" + shorten(it.text, 20));
                showMemory();
            });
        }
        if (items.size() == 1) {
            items.add("（还没有记录：任务成功一次即自动写入操作记忆；也可以说「记一下……」让我记住一件事）");
            acts.add(null);
        }

        ElderUi.show(new android.app.AlertDialog.Builder(this)
                .setTitle("记住的事（点一项就删除）")
                .setAdapter(ElderUi.list(this, items.toArray(new String[0])), (d, which) -> {
                    Runnable act = acts.get(which);
                    if (act != null) act.run();
                })
                .setNeutralButton("清空操作记忆", flows.isEmpty() ? null : (d, w) -> confirm(
                        "清空操作记忆？", "记住的做事步骤会全部删掉（共 " + flows.size() + " 条），"
                                + "「记一下」记住的内容不受影响。", "清空", () -> {
                            FlowMemory.clear(this);
                            say("操作记忆已清空（" + flows.size() + " 条）");
                        }))
                .setPositiveButton("关闭", null));
    }

    /**
     * 删除 / 清空 / 重置类操作的二次确认。
     *
     * <p>改版前这些按钮点一下就生效、且没有可见反馈；老年用户误触的概率高，
     * 而清空的数据不能恢复。确认框用红字标出危险按钮（{@link ElderUi#isDangerWord}）。
     */
    private void confirm(String title, String msg, String okText, Runnable act) {
        ElderUi.show(new android.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(msg)
                .setNegativeButton("不了", null)
                .setPositiveButton(okText, (d, w) -> act.run()));
    }

    /** 列表项用的短文本（记忆库条目可能很长，对话框一行放不下）。 */
    private static String shorten(String s, int n) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= n ? t : t.substring(0, n) + "…";
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

    /** 手机主页：提示写进大字提示框（Toast 两秒就消失，老人来不及看）；手表仍用 Toast。 */
    private void toast(String s) {
        if (!watchUi && tvLastMsg != null) {
            say(s);
            return;
        }
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }
}
