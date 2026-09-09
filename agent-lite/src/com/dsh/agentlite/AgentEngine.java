package com.dsh.agentlite;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Agent 决策循环：感知 → LLM → 执行 → 自适应等待 → 循环。
 * 全部能力经 AgentA11yService / 框架 API，零 root。
 */
public class AgentEngine {

    public interface Listener {
        void onLog(String line);
        void onStatus(boolean running, int step);
        void onScreen(Bitmap bmp);   // 动作后截图（可为 null）
        void onTap(int x, int y);    // 点击标记
        void onAsk(String question); // 模型提问：弹界面等待用户回答
    }

    private static final String TAG = "AgentEngine";
    private static AgentEngine sInstance;

    public static synchronized AgentEngine get(Context ctx) {
        if (sInstance == null) sInstance = new AgentEngine(ctx.getApplicationContext());
        return sInstance;
    }

    private final Context app;
    private volatile boolean running;
    private volatile boolean stopRequested;
    private volatile Thread thread;
    private volatile Listener listener;
    private int step;
    private String lastApp;        // 当前任务所在应用包名
    private boolean visionOnly;    // 纯视觉模式（节点被应用屏蔽）
    private boolean visionForced;  // 受限应用强制纯视觉（生态封锁档降级执行）
    private String pendingNote;    // list_apps 等动作产生的附注，拼进下一条感知消息
    private List<String[]> installedCache; // {label, pkg} 已安装应用缓存
    private boolean clipPending;   // 剪贴板已写入，引导下一步点「粘贴」
    private String rootPasteDone;  // 已用 keyevent279 粘过的文本（防同文死循环）
    private String borrowedIme;    // 任务中临时借用的输入法（任务结束归还）

    // ---- 豆包规格适配：接管 / 补充 / 授权 / 归档状态 ----
    private volatile boolean pauseRequested;  // 用户点了「接管」
    private volatile CountDownLatch pauseLatch;
    private final Queue<String> supplements = new ConcurrentLinkedQueue<>();
    private volatile CountDownLatch authLatch;
    private volatile int authDecision;
    private String currentTask;
    private boolean resultShown;              // 终态卡片已弹出（引擎收尾时保留卡片）
    private int guardSkipStep = -1;           // 交还接管后的一步内跳过敏感守卫（防同页重触发）

    private AgentEngine(Context ctx) { app = ctx; }

    public boolean isRunning() { return running; }

    public void setListener(Listener l) { listener = l; }

    /** 静态版日志：供 Activity 等非引擎处落盘排障。 */
    public static void staticLog(String msg) {
        try {
            android.app.Application app = MainActivity.appStatic();
            java.io.FileWriter w = new java.io.FileWriter(
                    new java.io.File(app.getExternalFilesDir(null), "engine.log"), true);
            w.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date()) + " " + msg + "\n");
            w.close();
        } catch (Exception ignored) { }
    }

    private void log(String s) {
        Log.i(TAG, s);
        Listener l = listener;
        if (l != null) l.onLog(s);
        try {
            java.io.FileWriter w = new java.io.FileWriter(
                    new java.io.File(app.getExternalFilesDir(null), "engine.log"), true);
            w.write(new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date()) + " " + s + "\n");
            w.close();
        } catch (Exception ignored) { }
    }

    private void status() {
        Listener l = listener;
        if (l != null) l.onStatus(running, step);
    }

    public synchronized boolean start(String task) {
        if (running) return false;
        if (AgentA11yService.get() == null) {
            log("无障碍服务未开启——请在设置里打开「Agent 无障碍服务」");
            return false;
        }
        stopRequested = false;
        running = true;
        step = 0;
        currentTask = task;
        resultShown = false;
        guardSkipStep = -1;
        pauseRequested = false;
        supplements.clear();
        OverlayController ovStart = overlay();
        if (ovStart != null) ovStart.taskStarted(task);
        KeepAliveService.start(app);
        thread = new Thread(() -> {
            try {
                runLoop(task);
            } catch (Throwable t) {
                log("任务异常终止: " + t);
                failTask("内部异常：" + t.getMessage());
            } finally {
                running = false;
                OverlayController ov = overlay();
                if (ov != null) ov.taskEnded(resultShown);
                status();
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    KeepAliveService.updateState(app, false);
                }, resultShown ? 4000 : 1000);
            }
        }, "agent-loop");
        thread.start();
        return true;
    }

    public void stop() {
        stopRequested = true;
        CountDownLatch p = pauseLatch;   // 接管等待中也要能停止（零延迟夺回控制权）
        if (p != null) p.countDown();
        CountDownLatch a = authLatch;    // 授权等待中同理
        if (a != null) { authDecision = 2; a.countDown(); }
        log("收到停止请求");
    }

    // ---- 覆盖层回调（报告 §2.1.1 兜底操作员通道） ----

    /** 用户点「接管」：下一步起点暂停执行（光晕熄灭、边框保留）。 */
    public void requestTakeover() {
        if (running) { pauseRequested = true; log("用户请求接管"); }
    }

    /** 用户点「交还 AI」：任务在原上下文上继续。 */
    public void requestResume() {
        CountDownLatch l = pauseLatch;
        if (l != null) l.countDown();
        KeepAliveService.updateProgress(app, currentTask, "AI 继续执行…", step, 0);
    }

    /** 执行中补充指令：入队，循环下一步注入对话（在线修正，不打断任务）。 */
    public void submitSupplement(String text) {
        if (running && text != null && !text.isEmpty()) {
            supplements.offer(text);
            log("用户补充：" + text);
        }
    }

    /** 授权三选项结果：0=单次允许 1=始终允许 2=拒绝。 */
    public void submitAuth(int d) {
        authDecision = d;
        CountDownLatch l = authLatch;
        if (l != null) l.countDown();
    }

    private OverlayController overlay() {
        AgentA11yService s = AgentA11yService.get();
        return s == null ? null : s.overlay();
    }

    // ---- 动作 schema（小布/小爱式：API/DeepLink/直达优先，界面兜底） ----
    private static final String SCHEMA = "\n\n动作必须是单个 JSON 对象，字段 action 取值：\n" +
            "【软件 API / DeepLink / 意图直达 —— 极速优先，能用 API 绝不手动点界面】\n" +
            "- {\"action\":\"open_api\",\"target\":\"app中文名或类型\",\"intent\":\"search|navigate|play|dial|browse\",\"param\":\"...\"}\n" +
            "  软件通用意图 API（自动构造标准 Android 意图直达）：\n" +
            "  * 地图搜索/导航: target=\"高德地图|百度地图\", intent=\"search\", param=\"深圳北站\"（直接调起官方 API 搜索页，0点击秒达）\n" +
            "  * 地图路线规划: target=\"高德地图|百度地图\", intent=\"navigate\", param=\"深圳北站\"（直接调起路线规划与导航 API）\n" +
            "  * 视频/内容搜索: target=\"哔哩哔哩\", intent=\"search\", param=\"大模型\"（直接调起客户端搜索结果页）\n" +
            "  * 音乐播放/搜索: target=\"网易云音乐|QQ音乐\", intent=\"search\", param=\"周杰伦\"\n" +
            "  * 电商商品搜索: target=\"淘宝|京东|拼多多|闲鱼|美团\", intent=\"search\", param=\"iPhone 16\"\n" +
            "  * 拨打电话/发短信: intent=\"dial\", param=\"10086\" 或 intent=\"sms\", param=\"10086:查话费\"\n" +
            "  * 网页直达: intent=\"browse\", param=\"https://...\"\n" +
            "- {\"action\":\"open_url\",\"url\":\"...\"}              打开指定 URL 或应用 Scheme（如 amapuri://、bilibili://、alipayqr://、tel:、weixin://）\n" +
            "- {\"action\":\"setting\",\"page\":\"wifi\"}             直达系统设置页(可选: wifi bluetooth display sound apps notifications location security battery storage date language accessibility airplane network vpn nfc cast developer deviceinfo home)\n" +
            "- {\"action\":\"app\",\"package\":\"包名\"}               启动应用（微信=com.tencent.mm 闲鱼=com.taobao.idlefish 淘宝=com.taobao.taobao 支付宝=com.eg.android.AlipayGphone 美团=com.sankuai.meituan 抖音=com.ss.android.ugc.aweme 哔哩哔哩=tv.danmaku.bili 小红书=com.xingin.xhs 高德地图=com.autonavi.minimap QQ=com.tencent.mobileqq 拼多多=com.xunmeng.pinduoduo 京东=com.jingdong.app.mall 携程=ctrip.android.view 微博=com.sina.weibo；其他应用填 package 为中文名即可）\n" +
            "- {\"action\":\"list_apps\"}                          查询已安装应用全量清单（不确定某应用是否安装/包名是什么时用）\n" +
            "- {\"action\":\"wifi\"} / {\"action\":\"bluetooth\"}      打开 WiFi/蓝牙开关面板(系统弹出面板上可直接开关)\n" +
            "- {\"action\":\"brightness\",\"level\":<0-255>}        直接调亮度(首次需授予修改系统设置权限)\n" +
            "- {\"action\":\"volume\",\"dir\":\"up|down|mute\"}       音量\n" +
            "- {\"action\":\"statusbar\",\"mode\":\"notifications|settings\"}  展开通知栏/快捷设置\n" +
            "- {\"action\":\"wake\"}                              点亮屏幕\n" +
            "【界面操作 —— 直达做不到时的兜底】\n" +
            "- {\"action\":\"tap\",\"index\":<节点编号>}            点击节点（首选编号；目标不在表中才用 \"x\",\"y\" 坐标）\n" +
            "- {\"action\":\"tap\",\"px\":0.50,\"py\":0.42}         比例坐标点击(px,py 为 0~1 的屏幕宽/高比例，从截图估计；节点表为空，或表里没有但截图中清楚可见的目标（纯图标按钮等）时使用；swipe 同理可用 px1,py1,px2,py2)\n" +
            "- {\"action\":\"longpress\",\"index\":<节点编号>}      长按节点(可带 \"dur\" 毫秒)\n" +
            "- {\"action\":\"swipe\",\"x1\":<int>,\"y1\":<int>,\"x2\":<int>,\"y2\":<int>,\"dur\":<int>} 滑动\n" +
            "- {\"action\":\"scroll\",\"direction\":\"up\"|\"down\"}    翻页\n" +
            "- {\"action\":\"key\",\"code\":<int>}                  按键(4=返回,3=主页,187=最近任务)\n" +
            "- {\"action\":\"edge_back\",\"side\":\"left|right\"}    边缘手势返回(从屏幕左/右边缘向内滑，全面屏手势的「返回」)\n" +
            "- {\"action\":\"text\",\"text\":\"...\"}                 输入文字(支持中文，替换输入框内容；先 tap 聚焦输入框)\n" +
            "- {\"action\":\"wait\",\"ms\":<int>}                   等待页面加载(最长 8000ms)\n" +
            "- {\"action\":\"ask\",\"question\":\"问题\"}             向用户提问并等待回答(敏感操作确认/信息不足时用；回答内容会作为下一轮输入)\n" +
            "- {\"action\":\"fail\",\"reason\":\"归因\"}              任务无法完成（应用受限/超时/目标不存在），必须给出明确归因，禁止用 done 冒充成功\n" +
            "- {\"action\":\"done\",\"summary\":\"完成说明\"}          任务已完成\n" +
            "【执行守则与复杂语义理解】\n" +
            "1. 软件 API 优先法则：凡搜索商品、搜索地点/导航、搜索视频、播放歌曲、拨打电话等需求，首选 open_api 或 open_url 执行系统/应用 API 一步到位，规避繁复的启动页广告与多级点击；\n" +
            "2. 复杂意图深层推理：理解隐式需求与多条件过滤（如「帮我查去深圳北站怎么走」→ 地图导航意图；「搜适合程序员的降噪耳机」→ 电商搜索意图并提取关键修饰词）；\n" +
            "3. 幂等与闭环：能直达不翻页；页面加载中先 wait；若动作执行后屏幕无任何变化，禁止重复点同一位置，必须换策略。\n" +
            "4. 敏感红线：涉及支付扣费、转账、发送敏感信息前必须使用 ask 提问获得许可。\n" +
            "节点编号每一步都会重新分配——只能使用最新节点表里的编号，严禁沿用上一步的旧编号。" +
            "任务只是「打开某应用/页面」时，看到该应用主界面出现就立即 done，不要继续探索；" +
            "「打开A并滚动/点击一下」类任务，完成那一个动作就 done。" +
            "用户任务里已明确授权的步骤直接执行，不要重复 ask 打断；仅支付/转账/发消息/删数据前必须 ask 确认。" +
            "设备锁屏/息屏时先 wake 点亮；仍是锁屏就用 ask 请用户解锁，绝不尝试绕过。\n" +
            "返回上级界面有三条路，按顺序尝试，一条没反应立刻换下一条：① key 4 系统返回；② tap 节点表里的「返回/←/back」节点（通常在屏幕左上角，坐标 x 很小、y 在顶部）；③ edge_back 边缘手势返回。\n" +
            "应用内部的设置页/详情页/聊天页/个人主页等都是该应用的一部分——页面跳转了不代表离开了应用，不要因此返回或重启；判断标准是任务进展。\n" +
            "节点数为 0 或屏幕全黑 = 应用正在加载（启动页/开屏广告），必须先 wait 2000~3000ms，绝对不要按 key 3/key 4/edge_back——那会把刚打开的应用退掉。开屏广告出现「跳过」节点时 tap 它。\n" +
            "只输出 JSON，不要输出任何其他文字、解释或 markdown 代码块。";

    // OEM 包名别名（ColorOS/一加实测）
    /** 常用应用中文名 → 包名（模型查表 + startApp 兜底解析）。 */
    private static final Map<String, String> APP_NAMES = new HashMap<>();
    static {
        APP_NAMES.put("微信", "com.tencent.mm");
        APP_NAMES.put("闲鱼", "com.taobao.idlefish");
        APP_NAMES.put("淘宝", "com.taobao.taobao");
        APP_NAMES.put("支付宝", "com.eg.android.AlipayGphone");
        APP_NAMES.put("美团", "com.sankuai.meituan");
        APP_NAMES.put("美团外卖", "com.sankuai.meituan.takeoutnew");
        APP_NAMES.put("抖音", "com.ss.android.ugc.aweme");
        APP_NAMES.put("哔哩哔哩", "tv.danmaku.bili");
        APP_NAMES.put("小红书", "com.xingin.xhs");
        APP_NAMES.put("高德地图", "com.autonavi.minimap");
        APP_NAMES.put("百度地图", "com.baidu.BaiduMap");
        APP_NAMES.put("QQ", "com.tencent.mobileqq");
        APP_NAMES.put("网易云音乐", "com.netease.cloudmusic");
        APP_NAMES.put("QQ音乐", "com.tencent.qqmusic");
        APP_NAMES.put("拼多多", "com.xunmeng.pinduoduo");
        APP_NAMES.put("京东", "com.jingdong.app.mall");
        APP_NAMES.put("携程", "ctrip.android.view");
        APP_NAMES.put("12306", "com.MobileTicket");
        APP_NAMES.put("钉钉", "com.alibaba.android.rimet");
        APP_NAMES.put("企业微信", "com.tencent.wework");
        APP_NAMES.put("微博", "com.sina.weibo");
        APP_NAMES.put("知乎", "com.zhihu.android");
        APP_NAMES.put("设置", "com.android.settings");
    }

    private static final Map<String, String[]> APP_ALIASES = new HashMap<>();
    static {
        APP_ALIASES.put("com.android.gallery3d", new String[]{"com.coloros.gallery3d", "com.oneplus.gallery"});
        APP_ALIASES.put("com.google.android.apps.photos", new String[]{"com.coloros.gallery3d", "com.oneplus.gallery"});
        APP_ALIASES.put("com.google.android.keep", new String[]{"com.coloros.note", "com.oneplus.note"});
        APP_ALIASES.put("com.android.notes", new String[]{"com.coloros.note", "com.oneplus.note"});
        APP_ALIASES.put("com.coloros.notepad", new String[]{"com.coloros.note"});
        APP_ALIASES.put("com.android.camera2", new String[]{"com.oplus.camera", "com.oneplus.camera"});
        APP_ALIASES.put("com.android.calculator2", new String[]{"com.coloros.calculator"});
        APP_ALIASES.put("com.android.music", new String[]{"com.heytap.music"});
    }

    private static final Map<String, String> SETTINGS_PAGES = new HashMap<>();
    static {
        SETTINGS_PAGES.put("settings", Settings.ACTION_SETTINGS);   // 通用设置首页（模型常直接说 settings）
        SETTINGS_PAGES.put("wifi", Settings.ACTION_WIFI_SETTINGS);
        SETTINGS_PAGES.put("wlan", Settings.ACTION_WIFI_SETTINGS);
        SETTINGS_PAGES.put("bluetooth", Settings.ACTION_BLUETOOTH_SETTINGS);
        SETTINGS_PAGES.put("display", Settings.ACTION_DISPLAY_SETTINGS);
        SETTINGS_PAGES.put("brightness", Settings.ACTION_DISPLAY_SETTINGS);
        SETTINGS_PAGES.put("sound", Settings.ACTION_SOUND_SETTINGS);
        SETTINGS_PAGES.put("apps", Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS);
        SETTINGS_PAGES.put("notifications", "android.settings.NOTIFICATION_SETTINGS");
        SETTINGS_PAGES.put("location", Settings.ACTION_LOCATION_SOURCE_SETTINGS);
        SETTINGS_PAGES.put("security", Settings.ACTION_SECURITY_SETTINGS);
        SETTINGS_PAGES.put("battery", Settings.ACTION_BATTERY_SAVER_SETTINGS);
        SETTINGS_PAGES.put("storage", Settings.ACTION_INTERNAL_STORAGE_SETTINGS);
        SETTINGS_PAGES.put("date", Settings.ACTION_DATE_SETTINGS);
        SETTINGS_PAGES.put("language", Settings.ACTION_LOCALE_SETTINGS);
        SETTINGS_PAGES.put("accessibility", Settings.ACTION_ACCESSIBILITY_SETTINGS);
        SETTINGS_PAGES.put("airplane", Settings.ACTION_AIRPLANE_MODE_SETTINGS);
        SETTINGS_PAGES.put("network", Settings.ACTION_WIRELESS_SETTINGS);
        SETTINGS_PAGES.put("vpn", Settings.ACTION_VPN_SETTINGS);
        SETTINGS_PAGES.put("nfc", Settings.ACTION_NFC_SETTINGS);
        SETTINGS_PAGES.put("cast", Settings.ACTION_CAST_SETTINGS);
        SETTINGS_PAGES.put("developer", Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        SETTINGS_PAGES.put("deviceinfo", Settings.ACTION_DEVICE_INFO_SETTINGS);
        SETTINGS_PAGES.put("home", Settings.ACTION_HOME_SETTINGS);
    }

    private void runLoop(String task) throws Exception {
        Prefs prefs = new Prefs(app);
        LlmClient llm = new LlmClient(prefs.baseUrl(), prefs.apiKey(), prefs.model())
                .visionModel(prefs.visionModel())
                .tokenListener((p, c, t) -> prefs.addTokens(p, c, t));
        int maxSteps = prefs.maxSteps();
        boolean vision = prefs.vision();

        log("任务启动: " + task + " (model=" + prefs.model() + (vision ? " +vision" : "") + ")");
        status();
        KeepAliveService.updateProgress(app, task, "正在理解任务…", 0, maxSteps);

        JSONArray messages = new JSONArray();
        // 记忆静默注入（报告 §7.2.2：调用无披露 UI，直接用于个性化执行）
        messages.put(LlmClient.textMsg("system", prefs.systemPrompt() + MemoryStore.promptBlock(app) + SCHEMA));

        // 语义理解前置：轻量规划模型把「人话」转成结构化执行简报（失败容错，不影响主流程）
        String plan = planTask(prefs, task);
        StringBuilder firstMsg = new StringBuilder("任务：").append(task);
        // 已装应用匹配注入：模型不再靠猜，直接拿到真实包名
        String matched = matchInstalled(task);
        if (!matched.isEmpty()) firstMsg.append("\n已安装应用匹配（直接使用这些包名）：\n").append(matched);
        if (plan != null) firstMsg.append("\n\n【规划简报】\n").append(plan);
        messages.put(LlmClient.textMsg("user", firstMsg.toString()));

        String lastKey = "";
        int repeats = 0;
        java.util.List<String> fpHistory = new java.util.ArrayList<>();
        int shotFails = 0;
        int zeroNodeSteps = 0;
        boolean visionOn = vision;
        lastApp = null;
        visionOnly = false;
        visionForced = false;
        pendingNote = null;
        boolean prevFailed = false;

        try {
        for (step = 1; step <= maxSteps; step++) {
            if (stopRequested) { log("任务已被用户停止"); history("终止", "用户停止"); return; }

            AgentA11yService svc = AgentA11yService.get();
            if (svc == null) {
                // 平板或部分定制系统无障碍服务可能在手势激烈时被系统短暂解绑，宽限重连 3 次
                for (int retry = 0; retry < 3; retry++) {
                    sleep(500);
                    svc = AgentA11yService.get();
                    if (svc != null) break;
                }
                if (svc == null) { failTask("无障碍服务断开，任务中止"); return; }
            }

            // 补充通道（报告 §4.2.2「补充」按钮：追加指令在线修正，任务不中断）
            StringBuilder sb = null;
            String supp;
            while ((supp = supplements.poll()) != null) {
                if (sb == null) sb = new StringBuilder();
                sb.append(supp).append('；');
            }
            if (sb != null) {
                messages.put(LlmClient.textMsg("user",
                        "【用户最新补充】" + sb + "这是用户在任务执行中的最新指令，优先级高于原任务——" +
                        "必须将其纳入剩余步骤一并完成，done 之前确认补充要求已满足。"));
            }

            // 主动接管检查点（报告 §2.1.1 兜底操作员：零延迟夺回控制权）
            if (pauseRequested && !enterPause(null)) {
                history("终止", "接管期间用户停止");
                return;
            }

            // 锁屏检测与自动点亮屏幕（锁屏保活协同）
            PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
            if (pm != null && !pm.isInteractive()) {
                log("检测到屏幕息屏/锁屏，自动激活点亮屏幕");
                try {
                    PowerManager.WakeLock wl = pm.newWakeLock(
                            PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE,
                            "agentlite:interactive_wake");
                    wl.acquire(1000);
                    wl.release();
                    sleep(800);
                } catch (Throwable t) {
                    log("点亮屏幕异常: " + t.getMessage());
                }
            }

            List<NodeInfo> nodes = svc.collectNodes();
            // 本助手拉应用引发的系统启动确认弹窗（「"Agent Lite"想要打开"XX"」）——
            // 用户已在事前授权过该应用，代点「仅本次允许」继续，不打断任务（报告 §5.1.1 授权覆盖语义）
            if (autoConfirmLaunch(svc, nodes)) { sleep(800); continue; }
            // 自动确定服务条款/用户协议/隐私政策弹窗
            if (autoAgreeTerms(svc, nodes)) { sleep(800); continue; }
            if (nodes.isEmpty()) {   // 加载中的空屏不值得问模型
                sleep(1200);
                nodes = svc.collectNodes();
            }
            zeroNodeSteps = nodes.isEmpty() ? zeroNodeSteps + 1 : 0;
            // 受限应用强制纯视觉：丢弃可读节点，保持「只看截图」的一致性
            if (visionForced && !nodes.isEmpty()) { nodes = new java.util.ArrayList<>(); zeroNodeSteps++; }
            log("第 " + step + " 步：感知到 " + nodes.size() + " 个节点" +
                    (visionForced ? "（纯视觉模式已生效）" :
                     zeroNodeSteps >= 2 ? "（连续空节点，疑似应用屏蔽无障碍）" : ""));

            // 敏感场景守卫：只针对极端扣款场景防误触（不阻断应用正常启动与浏览搜索）
            if (step > guardSkipStep && !nodes.isEmpty()) {
                String hit = SensitiveGuard.scan(nodes);
                if (hit != null) {
                    log("命中敏感场景：" + hit);
                    if (!enterPause(hit)) {
                        history("终止", "敏感节点接管期间用户停止");
                        return;
                    }
                    guardSkipStep = step + 1;   // 交还后下一步免扫描，防同页重复触发
                    step--;                      // 接管不消耗决策步
                    continue;
                }
            }
            String appHint = lastApp == null ? "" :
                    "（当前在应用 " + lastApp + " 内，其二级/三级页面都是它的一部分，不要因界面变化就返回或重启）\n";

            // 视觉：截图降采样为 ≤640px JPEG data URL；连续失败自动降级纯节点模式
            JSONObject perceive;
            String prompt;
            boolean needShot = visionOn && (visionForced || visionOnly || step == 1 || nodes.size() < 5
                    || prevFailed || zeroNodeSteps >= 1);
            prevFailed = false;
            if (needShot) {
                Bitmap bmp = svc.screenshot();
                String dataUrl = bmpToDataUrl(bmp, 480, 45);
                if (dataUrl != null) {
                    shotFails = 0;
                    int lum = meanLuma(bmp);
                    log("截图亮度≈" + lum + (lum < 8 ? "（黑屏，截图可能被屏蔽）" : ""));
                    if ((visionForced || zeroNodeSteps >= 2) && lum >= 8) {
                        // 纯视觉模式：节点被屏蔽但像素可见 → 坐标驱动
                        if (!visionOnly) { visionOnly = true; log("进入纯视觉模式：改用比例坐标操作"); }
                        android.graphics.Rect wb = svc.getSystemService(android.view.WindowManager.class)
                                .getCurrentWindowMetrics().getBounds();
                        prompt = appHint + (visionForced
                                ? "【纯视觉模式】该应用为受限应用，禁止读取界面结构——你只能看截图操作。" : "") +
                                "该应用屏蔽了无障碍节点（节点表为空），只能看截图用比例坐标操作。\n" +
                                "屏幕宽=" + wb.width() + " 高=" + wb.height() + "。\n" +
                                "tap/longpress 用 px,py（0~1 比例），swipe 用 px1,py1,px2,py2。\n" +
                                "关键守则：\n" +
                                "1. 点中心：点击目标按钮/搜索框/Tab 的几何中心，不要点在边缘；\n" +
                                "2. 禁止重复点击：每次执行动作后页面必须有变化；若上一动作未生效，切勿重复点击同一区域，立即尝试轻微调整坐标或滑动(swipe)寻找；\n" +
                                "3. 键盘输入：看到输入框焦点已亮起时直接使用 text 动作，如果无效使用 key 动作；\n" +
                                "4. 支付/密码/验证码等敏感环节必须 ask 请用户操作。";
                    } else {
                        visionOnly = visionForced;
                        prompt = appHint + (nodes.isEmpty()
                                ? "当前屏幕无节点（加载中）。请 wait 等待。"
                                : NodeInfo.toPrompt(nodes, 25));
                    }
                    perceive = LlmClient.visionMsg("user", prompt + "\n\n附屏幕截图。", dataUrl);
                    pushScreen(bmp);
                } else {
                    shotFails++;
                    if (shotFails >= 3) {
                        visionOn = false;
                        log("截图连续失败（系统限频），转为纯节点模式");
                    }
                    perceive = LlmClient.textMsg("user", nodes.isEmpty()
                            ? "当前屏幕无节点且截图失败。请 wait 重试。"
                            : NodeInfo.toPrompt(nodes, 25));
                }
            } else {
                perceive = LlmClient.textMsg("user", appHint + (nodes.isEmpty()
                        ? "当前屏幕无节点（加载中）。请 wait 等待。"
                        : NodeInfo.toPrompt(nodes, 25)));
            }
            messages.put(perceive);

            OverlayController ovThink = overlay();
            if (ovThink != null) ovThink.progress("第 " + step + " 步 · AI 思考中…");
            KeepAliveService.updateProgress(app, task, "第 " + step + " 步 · AI 思考中…", step, maxSteps);

            String reply;
            try {
                reply = llm.chat(messages);
            } catch (Exception e) {
                failTask("模型调用失败：" + trim(e.getMessage(), 200));
                return;
            }

            JSONObject action;
            try {
                action = ActionParser.parse(reply);
            } catch (Exception e) {
                log("LLM 输出无法解析: " + e.getMessage());
                messages.put(LlmClient.textMsg("assistant", reply));
                messages.put(LlmClient.textMsg("user", "输出不是合法 JSON 动作，请重新只输出一个 JSON 动作。"));
                continue;
            }

            String kind = action.optString("action", "");
            if ("ask".equals(kind)) {
                String q = action.optString("question", "");
                log("向用户提问: " + q);
                OverlayController ovAsk = overlay();
                if (ovAsk != null) {
                    ovAsk.showAsk(q);
                }
                String answer = askUser(q);
                if (ovAsk != null) {
                    ovAsk.hideCard();
                }
                log("用户回答: " + answer);
                messages.put(LlmClient.textMsg("assistant", reply));
                messages.put(LlmClient.textMsg("user",
                        "用户回答：「" + trim(answer, 200) + "」。请据此继续（或 done）。"));
                continue;
            }
            if ("done".equals(kind)) {
                // 完成卡片（报告 §4.3.1）：绿色语义，摘要+产物收口
                String summary = action.optString("summary", "完成");
                log("任务完成：" + summary);
                history("完成", summary);
                OverlayController ov = overlay();
                if (ov != null) { resultShown = true; ov.showDone(summary); }
                KeepAliveService.updateProgress(app, task, "完成: " + summary, step, maxSteps);
                return;
            }
            if ("fail".equals(kind)) {
                // 失败话术范式（报告 §4.3.2）：归因由模型给出，引擎统一加工
                failTask(action.optString("reason", "模型未给出归因"));
                return;
            }

            String execErr = null;
            String label = kind;
            try {
                label = exec(svc, action, nodes);
            } catch (StoppedException se) {
                log("任务已被用户停止");
                history("终止", "用户停止");
                return;
            } catch (TaskFailException t) {
                failTask(t.getMessage());
                return;
            } catch (Exception e) {
                execErr = e.getMessage();
            }

            if (execErr != null) {
                prevFailed = true;
                log("执行 " + label + " 失败：" + execErr);
                messages.put(LlmClient.textMsg("assistant", reply));
                messages.put(LlmClient.textMsg("user", "动作执行失败：" + trim(execErr, 300) +
                        "。请换一种方式继续（例如 tap 屏幕上的图标/元素），或 done。"));
                continue;
            }
            log("第 " + step + " 步：执行 " + label);
            OverlayController ovProg = overlay();
            String fLabel = friendlyLabel(label);
            if (ovProg != null) ovProg.progress("第 " + step + " 步 · " + fLabel);
            KeepAliveService.updateProgress(app, task, "第 " + step + " 步 · " + fLabel, step, maxSteps);
            boolean guidePaste = clipPending;
            clipPending = false;

            // 自适应等待：感知树变化或超时（150ms 轮询，上限 1.0s，提速）
            if (!"wait".equals(kind)) waitForChange(svc, nodes);

            // 死循环与重复检测（含比例坐标与像素坐标聚类，防反复点同一区域）
            int ax = action.optInt("x", (int) (action.optDouble("px", 0.0) * 1000));
            int ay = action.optInt("y", (int) (action.optDouble("py", 0.0) * 1000));
            String actKey = kind + "|" + action.opt("index") + "|" + (ax / 30) + "," + (ay / 30);
            repeats = actKey.equals(lastKey) ? repeats + 1 : 0;
            lastKey = actKey;

            // 无进展看门狗：屏幕指纹连续 6 步不变 → 任务卡死，中止
            List<NodeInfo> fpNodes = svc.collectNodes();
            fpHistory.add(fpNodes.isEmpty() ? "empty-" + (step % 2) : NodeInfo.fingerprint(fpNodes));
            if (fpHistory.size() >= 6) {
                java.util.List<String> tail = fpHistory.subList(fpHistory.size() - 6, fpHistory.size());
                boolean allSame = true;
                for (int i = 1; i < tail.size(); i++) if (!tail.get(i).equals(tail.get(0))) { allSame = false; break; }
                if (allSame) {
                    failTask("屏幕连续 6 步无任何变化，疑似卡死，已自动停止（可缩小任务范围后重试）");
                    return;
                }
            }
            String nextMsg = guidePaste
                    ? "目标文字已写入剪贴板并已长按输入框。屏幕上应弹出了含「粘贴」的菜单——请立即 tap「粘贴」按钮完成输入（节点被屏蔽时用 px/py 比例坐标点它）。没弹菜单就再 longpress 一次输入框，或换其他方式。"
                    : "已执行动作，这是执行后的屏幕，请继续下一步（或 done）。";
            if (repeats >= 1) {
                nextMsg = "警告：你已连续 " + (repeats + 1) + " 次执行完全相同但没有进展的动作。" +
                        "禁止重复执行！当前页面未发生变化，必须立即换策略：换用不同坐标、通过 scroll 翻页找目标，或者返回上一页重试。";
            }
            if (pendingNote != null) {   // list_apps 等动作产出拼进下一条输入
                nextMsg = pendingNote + "\n" + nextMsg;
                pendingNote = null;
            }

            messages.put(LlmClient.textMsg("assistant", reply));
            messages.put(LlmClient.textMsg("user", nextMsg));
            // 保留 system + 任务 + 最近 8 条
            while (messages.length() > 10) {
                JSONArray trimmed = new JSONArray();
                trimmed.put(messages.get(0));
                trimmed.put(messages.get(1));
                for (int i = messages.length() - 8; i < messages.length(); i++) trimmed.put(messages.get(i));
                messages = trimmed;
            }
            status();
        }
        } finally {
            restoreIme();   // 归还借用的输入法
        }
        failTask("达到最大步数 " + maxSteps + " 仍未完成，可缩小任务范围后重试");
    }

    // ---- 豆包规格：终态出口 / 接管 / 授权 ----

    /** 任务级失败（区别于单步执行异常）：直接终态，不走重试。 */
    private static final class TaskFailException extends Exception {
        TaskFailException(String msg) { super(msg); }
    }

    /** 授权等待期间用户停止：静默终态，不弹失败卡片。 */
    private static final class StoppedException extends Exception { }

    /** 失败统一出口（报告 §4.3.2）：「任务失败——」起头、明示归因、归档。 */
    private void failTask(String reason) {
        String msg = "任务失败——" + reason;
        log(msg);
        history("失败", reason);
        KeepAliveService.updateProgress(app, currentTask, "未完成: " + reason, step, 0);
        OverlayController ov = overlay();
        if (ov != null) { resultShown = true; ov.showFail(reason); }
    }

    private void history(String result, String detail) {
        TaskHistory.append(app, currentTask == null ? "" : currentTask, result, detail, step);
    }

    /**
     * 进入接管/暂停态（报告 §5.2.3 光晕消/边框留二值信号）。
     * reason != null = 敏感场景强制接管（弹「请手动操作」卡片）；null = 用户主动接管。
     * 返回 false = 接管期间用户停止了任务。
     */
    private boolean enterPause(String reason) {
        pauseRequested = false;
        OverlayController ov = overlay();
        String pauseText = reason == null ? "已接管 · 任务暂停" : "请手动操作 · 任务暂停";
        log(reason == null ? "用户接管，任务暂停（呼吸灯切为素色轮廓）" : "敏感节点强制接管");
        KeepAliveService.updateProgress(app, currentTask, pauseText, step, 0);
        if (ov != null) {
            ov.setPaused(reason);
            if (reason != null) ov.showTakeover(reason);
        }
        pauseLatch = new CountDownLatch(1);
        while (!stopRequested) {
            try {
                if (pauseLatch.await(400, TimeUnit.MILLISECONDS)) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        pauseLatch = null;
        if (ov != null) { ov.hideCard(); ov.clearPaused(); }
        if (stopRequested) return false;
        log("用户已交还控制权，AI 继续执行");
        return true;
    }

    /** 事前授权三选项（报告 §5.1.1）：阻塞等决策；返回 0=单次 1=始终 2=拒绝。 */
    private int requestAuth(String appLabel) {
        OverlayController ov = overlay();
        if (ov == null) return 0;
        log("事前授权询问：「" + appLabel + "」");
        authLatch = new CountDownLatch(1);
        authDecision = 0;
        ov.showAuth(appLabel);
        while (!stopRequested) {
            try {
                if (authLatch.await(400, TimeUnit.MILLISECONDS)) break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        authLatch = null;
        ov.hideCard();
        int d = stopRequested ? 2 : authDecision;
        log("授权决策：" + (d == 0 ? "单次允许" : d == 1 ? "始终允许" : "拒绝"));
        return d;
    }

    /**
     * 检测并代点「"本助手"想要打开"XX"」系统启动确认弹窗（ColorOS/MagicOS 均有）。
     * 仅当弹窗由本应用拉起目标应用引发、且目标是「仅本次允许/允许」按钮时生效；返回 true=已代点。
     */
    private boolean autoConfirmLaunch(AgentA11yService svc, List<NodeInfo> nodes) {
        boolean isLaunchConfirm = false;
        for (NodeInfo n : nodes) {
            String l = n.label();
            if (l.contains("想要打开") || l.contains("想要打开“") || l.contains("想打开")) {
                isLaunchConfirm = true; break;
            }
        }
        if (!isLaunchConfirm) return false;
        for (NodeInfo n : nodes) {
            String l = n.label();
            if (n.clickable && (l.equals("仅本次允许") || l.equals("允许") || l.equals("30天内允许"))) {
                log("检测到本助手引发的跨应用启动确认，代点「" + l + "」");
                return svc.tap(n.cx, n.cy);
            }
        }
        return false;
    }

    /**
     * 自动确定服务条款/隐私政策/用户协议弹窗（常见于首次安装或应用更新后）。
     * 识别「服务协议/条款/隐私政策」相关的提示，并代点「同意并继续/同意/同意并使用/接受」。
     */
    private boolean autoAgreeTerms(AgentA11yService svc, List<NodeInfo> nodes) {
        boolean isTermsDialog = false;
        for (NodeInfo n : nodes) {
            String l = n.label();
            if (l.contains("服务条款") || l.contains("服务协议") || l.contains("隐私政策")
                    || l.contains("用户协议") || l.contains("隐私协议") || l.contains("用户许可")
                    || l.contains("个人信息保护政策") || l.contains("个人信息保护指引")
                    || l.contains("协议与隐私政策") || l.contains("同意协议")) {
                isTermsDialog = true;
                break;
            }
        }
        if (!isTermsDialog) return false;

        // 1. 优先点击包含明确「同意」并有动作倾向的按钮
        for (NodeInfo n : nodes) {
            String l = n.label();
            if (n.clickable && (l.equals("同意并继续") || l.equals("同意") || l.equals("同意并使用")
                    || l.equals("同意并接受") || l.equals("我同意") || l.equals("已阅读并同意")
                    || l.equals("接受") || l.equals("同意并进入") || l.equals("确定"))) {
                log("检测到服务条款/用户协议弹窗，自动确定「" + l + "」");
                if (n.index >= 0 && svc.tapNode(n.index)) return true;
                return svc.tap(n.cx, n.cy);
            }
        }

        // 2. 检查是否有未勾选的协议复选框（部分 App 要求勾选后才亮起同意按钮）
        for (NodeInfo n : nodes) {
            String l = n.label();
            if (n.clickable && (l.contains("已阅读并同意") || l.contains("同意《") || l.contains("阅读并同意"))) {
                log("检测到服务条款勾选框，自动代点勾选「" + l + "」");
                if (n.index >= 0 && svc.tapNode(n.index)) { sleep(300); }
                else { svc.tap(n.cx, n.cy); sleep(300); }
            }
        }

        // 3. 再次查找可点击的同意/继续/进入按钮
        for (NodeInfo n : nodes) {
            String l = n.label();
            if (n.clickable && (l.contains("同意") || l.equals("确定") || l.equals("进入") || l.equals("开始使用"))) {
                log("自动确定服务条款「" + l + "」");
                if (n.index >= 0 && svc.tapNode(n.index)) return true;
                return svc.tap(n.cx, n.cy);
            }
        }
        return false;
    }

    private void pushScreen(Bitmap bmp) {
        Listener l = listener;
        if (l != null) l.onScreen(bmp);
    }

    private void pushTap(int x, int y) {
        Listener l = listener;
        if (l != null) l.onTap(x, y);
    }

    private void waitForChange(AgentA11yService svc, List<NodeInfo> prev) {
        String fp = NodeInfo.fingerprint(prev);
        sleep(60);
        long deadline = System.currentTimeMillis() + 600;
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested) return;
            List<NodeInfo> cur = svc.collectNodes();
            if (!NodeInfo.fingerprint(cur).equals(fp)) return;
            sleep(80);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    // ---- 已装应用发现（PackageManager 真实清单，模型不再靠猜） ----

    /** 已安装可启动应用清单 {label, pkg}，按名称排序，带缓存。 */
    private synchronized List<String[]> installedApps() {
        if (installedCache != null) return installedCache;
        List<String[]> out = new java.util.ArrayList<>();
        try {
            PackageManager pm = app.getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            for (android.content.pm.ResolveInfo r : pm.queryIntentActivities(i, 0)) {
                String pkg = r.activityInfo.packageName;
                if (app.getPackageName().equals(pkg)) continue;   // 自己不入列
                CharSequence label = r.loadLabel(pm);
                if (label == null) continue;
                out.add(new String[]{label.toString(), pkg});
            }
            out.sort((x, y) -> x[0].compareToIgnoreCase(y[0]));
        } catch (Exception e) {
            Log.w(TAG, "installedApps: " + e);
        }
        installedCache = out;
        return out;
    }

    /** 任务文本 ↔ 已装应用名双向匹配，产出注入提示行（最多 6 条）。 */
    private String matchInstalled(String task) {
        StringBuilder b = new StringBuilder();
        int n = 0;
        for (String[] ia : installedApps()) {
            if (n >= 6) break;
            String label = ia[0];
            if (label.length() < 2) continue;
            if (task.contains(label)) { b.append(label).append('=').append(ia[1]).append('\n'); n++; }
        }
        // cheat sheet 里的常用名也匹配一遍（覆盖「京东/JD」这类别名场景）
        for (Map.Entry<String, String> en : APP_NAMES.entrySet()) {
            if (n >= 6) break;
            if (task.contains(en.getKey()) && b.indexOf(en.getValue()) < 0) {
                b.append(en.getKey()).append('=').append(en.getValue()).append('\n'); n++;
            }
        }
        return b.toString();
    }

    private static String friendlyLabel(String raw) {
        if (raw == null) return "正在执行…";
        if (raw.startsWith("app ")) return "打开应用 " + raw.substring(4);
        if (raw.startsWith("open_api")) return "调用应用 API 直达";
        if (raw.startsWith("open_url")) return "打开应用直达链接";
        if (raw.startsWith("tap#")) return "点击界面元素 " + raw.substring(4);
        if (raw.startsWith("tap")) return "点击目标位置";
        if (raw.startsWith("longpress")) return "长按界面元素";
        if (raw.startsWith("text ")) return "输入文本内容";
        if (raw.startsWith("scroll") || raw.startsWith("swipe")) return "滑动屏幕浏览";
        if (raw.startsWith("key")) return "模拟按键操作";
        if (raw.startsWith("list_apps")) return "匹配已装应用";
        return raw;
    }

    // ---- 语义理解前置（轻量规划模型：人话 → 结构化执行简报） ----

    /** 用轻量规划模型把口语任务极速转成结构化执行提示（限制 120 字，秒级响应）。 */
    private String planTask(Prefs prefs, String task) {
        try {
            String plannerModel = prefs.plannerModel();
            if (plannerModel == null || plannerModel.isEmpty()) return null;
            OverlayController ov = overlay();
            if (ov != null) ov.progress("规划路径中…");
            LlmClient planner = new LlmClient(prefs.baseUrl(), prefs.apiKey(), plannerModel)
                    .tokenListener((p, c, t) -> prefs.addTokens(p, c, t));
            JSONArray msgs = new JSONArray();
            msgs.put(LlmClient.textMsg("system",
                    "你是手机自动化极速规划引擎。提炼用户任务的结构化执行路径，输出 3 行纯文本，严禁废话：\n" +
                    "意图/直达建议：(优先建议 open_api/open_url/setting，无需点击)\n" +
                    "推荐应用：(目标 App 中文名)\n" +
                    "步骤提炼：(1~3 步核心要点)"));
            msgs.put(LlmClient.textMsg("user", task));
            String plan = planner.chat(msgs);
            if (plan == null || plan.trim().isEmpty()) return null;
            log("规划简报已就绪（" + plannerModel + "）");
            return plan.trim();
        } catch (Exception e) {
            log("规划模型调用跳过（直接按主任务执行）：" + trim(e.getMessage(), 120));
            return null;
        }
    }

    // ---- 动作执行（零 root 实现） ----

    private String exec(AgentA11yService svc, JSONObject a, List<NodeInfo> currentNodes) throws Exception {
        String kind = a.optString("action", "");
        switch (kind) {
            case "tap": {
                // 优先节点直点（ACTION_CLICK 爬可点祖先，治「搜索栏坐标点不动」），失败再手势兜底
                if (a.has("index") && svc.tapNode(a.optInt("index"))) {
                    return "tap#" + a.optInt("index") + "(直点)";
                }
                int[] xy = resolvePoint(svc, a, currentNodes);
                pushTap(xy[0], xy[1]);
                if (!svc.tap(xy[0], xy[1])) {
                    if (RootShell.available(app) && RootShell.tap(xy[0], xy[1])) {
                        return (a.has("index") ? "tap#" + a.optInt("index") : "tap") + "(root)";
                    }
                    throw new Exception("手势被系统取消");
                }
                return a.has("index") ? "tap#" + a.optInt("index") : "tap";
            }
            case "longpress": {
                int[] xy = resolvePoint(svc, a, currentNodes);
                pushTap(xy[0], xy[1]);
                int dur = a.optInt("dur", 900);
                if (!svc.longPress(xy[0], xy[1], dur)) throw new Exception("长按手势被系统取消");
                return "longpress" + (a.has("index") ? "#" + a.optInt("index") : "");
            }
            case "list_apps": {
                // 已装应用发现：模型随时可查全量清单
                StringBuilder la = new StringBuilder("已安装应用（名称=包名，仅列前 80 个）：\n");
                List<String[]> all = installedApps();
                int cap = Math.min(all.size(), 80);
                for (int i = 0; i < cap; i++) la.append(all.get(i)[0]).append('=').append(all.get(i)[1]).append('\n');
                pendingNote = la.toString();
                return "list_apps(" + all.size() + ")";
            }
            case "swipe": {
                int x1 = a.optInt("x1"), y1 = a.optInt("y1"),
                    x2 = a.optInt("x2"), y2 = a.optInt("y2");
                if (a.has("px1")) {
                    android.graphics.Rect wb = svc.getSystemService(android.view.WindowManager.class)
                            .getCurrentWindowMetrics().getBounds();
                    x1 = (int) (a.optDouble("px1", 0) * wb.width());
                    y1 = (int) (a.optDouble("py1", 0) * wb.height());
                    x2 = (int) (a.optDouble("px2", 0) * wb.width());
                    y2 = (int) (a.optDouble("py2", 0) * wb.height());
                }
                boolean ok = svc.swipe(x1, y1, x2, y2, a.optInt("dur", 400));
                if (!ok) {
                    if (RootShell.available(app) && RootShell.swipe(x1, y1, x2, y2, a.optInt("dur", 400))) {
                        return "swipe(root)";
                    }
                    throw new Exception("滑动手势被系统取消");
                }
                return "swipe";
            }
            case "scroll": {
                int h = app.getResources().getDisplayMetrics().heightPixels;
                int w = app.getResources().getDisplayMetrics().widthPixels;
                boolean down = "down".equals(a.optString("direction", "down"));
                // 语义：direction=down 表示内容向下翻（手指上滑）
                boolean ok = down ? svc.swipe(w / 2, h / 4, w / 2, h * 3 / 4, 400)
                                  : svc.swipe(w / 2, h * 3 / 4, w / 2, h / 4, 400);
                if (!ok) throw new Exception("翻页手势被系统取消");
                return "scroll " + (down ? "down" : "up");
            }
            case "key": {
                int code = a.optInt("code", 4);
                boolean ok;
                if (code == 3) ok = svc.goHome();
                else if (code == 187) ok = svc.goRecents();
                else ok = svc.goBack();
                if (!ok) throw new Exception("全局动作失败");
                return "key " + code;
            }
            case "edge_back": {
                android.graphics.Rect wb = app.getSystemService(android.view.WindowManager.class)
                        .getCurrentWindowMetrics().getBounds();
                int w = wb.width(), h = wb.height();
                int y = (int) (h * 0.45);
                boolean fromLeft = !"right".equals(a.optString("side", "left"));
                boolean ok = fromLeft
                        ? svc.swipe(2, y, (int) (w * 0.35), y, 300)
                        : svc.swipe(w - 2, y, (int) (w * 0.65), y, 300);
                if (!ok) throw new Exception("边缘手势被系统取消");
                return "edge_back " + (fromLeft ? "left" : "right");
            }
            case "back":
                svc.goBack(); return "back";
            case "home":
                svc.goHome(); lastApp = null; return "home";
            case "text": {
                String text = a.optString("text", "");
                if (!visionOnly) {
                    String err = svc.setText(text, a.optBoolean("append", false));
                    if (err == null) return "text";
                    // ACTION_SET_TEXT 被拒 → 尝试 Agent 键盘通道
                }
                if (commitViaMergedIme(text, !a.optBoolean("append", false))) {
                    return "text(缝合键盘)";
                }
                if (injectViaClipboard(svc, a, text)) {
                    return "text(剪贴板待粘贴)";
                }
                throw new Exception("输入失败：无障碍写入被拒、缝合键盘未安装、剪贴板写入被系统拒绝（可在权限管理里允许本应用写剪贴板）");
            }
            case "app": {
                String pkg = a.optString("package", "");
                if (!pkg.contains(".") && APP_NAMES.containsKey(pkg)) pkg = APP_NAMES.get(pkg);
                if (!pkg.contains(".")) {   // cheat sheet 未命中 → 已装应用模糊解析（名称含查询词）
                    String q = pkg;
                    for (String[] ia : installedApps()) {
                        if (ia[0].contains(q) || q.contains(ia[0])) { pkg = ia[1]; break; }
                    }
                    if (!pkg.contains("."))
                        throw new Exception("未安装或找不到「" + q + "」——可用 list_apps 查看已安装应用清单");
                }
                // 生态三档检查（报告 §9.3.1：三档梯度前置显形）
                EcoPolicy eco = new EcoPolicy(app);
                EcoPolicy.Entry e = eco.find(pkg);
                if (e == null) e = eco.find(a.optString("package", ""));
                String appLabel = e != null ? e.name : pkg;
                if (e == null) {   // 授权弹窗用中文名而非包名（反查别名表 + 已装清单）
                    for (Map.Entry<String, String> en : APP_NAMES.entrySet()) {
                        if (en.getValue().equals(pkg)) { appLabel = en.getKey(); break; }
                    }
                    for (String[] ia : installedApps()) {
                        if (ia[1].equals(pkg)) { appLabel = ia[0]; break; }
                    }
                }
                // 彻底关闭任何阻断性安全拦截，允许直接启动所有 App
                visionForced = false;
                startApp(pkg);
                sleep(1000);   // 应用启动等待调优为 1.0s，大幅加速执行响应
                lastApp = pkg;
                return "app " + pkg;
            }
            case "setting": {
                String page = a.optString("page", "");
                String intentAction = SETTINGS_PAGES.get(page.toLowerCase());
                if (intentAction == null) throw new Exception("未知设置页 " + page);
                startActivity(new Intent(intentAction));
                return "setting " + page;
            }
            case "open_url": {
                String url = a.optString("url", "");
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                return "open_url";
            }
            case "open_api": {
                String target = a.optString("target", "").toLowerCase();
                String intent = a.optString("intent", "search").toLowerCase();
                String param = a.optString("param", "");
                String encParam = Uri.encode(param);
                Intent apiIntent = null;

                // 1. 地图搜索与导航 API (高德/百度/腾讯/通用地理位置)
                if (target.contains("高德") || target.contains("amap")) {
                    if ("navigate".equals(intent)) {
                        apiIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("androidamap://route?sourceApplication=AgentLite&dname=" + encParam + "&dev=0&m=0"));
                    } else {
                        apiIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("androidamap://poi?sourceApplication=AgentLite&keywords=" + encParam));
                    }
                } else if (target.contains("百度地图") || target.contains("baidu")) {
                    if ("navigate".equals(intent)) {
                        apiIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("baidumap://map/direction?destination=" + encParam + "&mode=driving"));
                    } else {
                        apiIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("baidumap://map/place/search?query=" + encParam));
                    }
                }
                // 2. 视频与社区搜索 (B站/小红书/抖音/微博)
                else if (target.contains("哔哩哔哩") || target.contains("b站") || target.contains("bilibili")) {
                    apiIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("bilibili://search?keyword=" + encParam));
                } else if (target.contains("小红书")) {
                    apiIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("xhsdiscover://search/result?keyword=" + encParam));
                } else if (target.contains("微博")) {
                    apiIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("sinaweibo://searchall?q=" + encParam));
                }
                // 3. 电商平台搜索 (淘宝/京东/拼多多/闲鱼/美团)
                else if (target.contains("淘宝")) {
                    apiIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("taobao://s.taobao.com/search?q=" + encParam));
                } else if (target.contains("京东")) {
                    apiIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("openapp.jdmobile://virtual?params={\"category\":\"jump\",\"des\":\"search\",\"keyWord\":\"" + param + "\"}"));
                } else if (target.contains("闲鱼")) {
                    apiIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("fleamarket://search?keyword=" + encParam));
                } else if (target.contains("拼多多")) {
                    apiIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("pinduoduo://com.xunmeng.pinduoduo/search_result.html?search_key=" + encParam));
                } else if (target.contains("美团")) {
                    apiIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("imeituan://www.meituan.com/search?q=" + encParam));
                }
                // 4. 音乐播放与搜索 (网易云音乐/QQ音乐)
                else if (target.contains("网易云") || target.contains("cloudmusic")) {
                    apiIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("orpheus://search/" + encParam));
                } else if (target.contains("qq音乐")) {
                    apiIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("qqmusic://qq.com/other/search?key=" + encParam));
                }
                // 5. 系统通讯 (电话/短信/浏览器)
                else if ("dial".equals(intent)) {
                    apiIntent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + param));
                } else if ("sms".equals(intent)) {
                    String[] sp = param.split(":", 2);
                    String num = sp[0];
                    String body = sp.length > 1 ? sp[1] : "";
                    apiIntent = new Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + num));
                    if (!body.isEmpty()) apiIntent.putExtra("sms_body", body);
                } else if ("browse".equals(intent)) {
                    String u = param.startsWith("http") ? param : "https://" + param;
                    apiIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                }

                // 6. 若特定协议未匹配，回退标准通用地理搜索或网页搜索
                if (apiIntent == null) {
                    if ("search".equals(intent) && (target.contains("地图") || target.contains("位置") || target.contains("导航"))) {
                        apiIntent = new Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + encParam));
                    } else {
                        apiIntent = new Intent(Intent.ACTION_WEB_SEARCH);
                        apiIntent.putExtra(android.app.SearchManager.QUERY, param);
                    }
                }

                try {
                    startActivity(apiIntent);
                    sleep(1000);
                    return "open_api: " + target + " " + intent + " (" + param + ")";
                } catch (Throwable t) {
                    log("open_api 失败，改用标准应用打开: " + t.getMessage());
                    // 降级使用包名直启
                    startApp(target);
                    sleep(1000);
                    return "open_api 降级 app: " + target;
                }
            }
            case "wifi": {
                startActivity(new Intent(Settings.Panel.ACTION_WIFI));
                return "wifi 面板";
            }
            case "bluetooth": {
                startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
                return "bluetooth 设置";
            }
            case "brightness": {
                int level = Math.max(0, Math.min(255, a.optInt("level", 128)));
                if (!Settings.System.canWrite(app)) {
                    startActivity(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:" + app.getPackageName())));
                    throw new Exception("需要先授予「修改系统设置」权限（已打开授权页），授权后重试");
                }
                Settings.System.putInt(app.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS_MODE,
                        Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(app.getContentResolver(),
                        Settings.System.SCREEN_BRIGHTNESS, level);
                return "brightness " + level;
            }
            case "volume": {
                String dir = a.optString("dir", "up");
                AudioManager am = (AudioManager) app.getSystemService(Context.AUDIO_SERVICE);
                int adj = "down".equals(dir) ? AudioManager.ADJUST_LOWER
                        : "mute".equals(dir) ? AudioManager.ADJUST_MUTE
                        : AudioManager.ADJUST_RAISE;
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, adj, AudioManager.FLAG_SHOW_UI);
                return "volume " + dir;
            }
            case "statusbar": {
                String mode = a.optString("mode", "notifications");
                boolean ok = "settings".equals(mode) ? svc.quickSettings() : svc.notifications();
                if (!ok) throw new Exception("状态栏动作失败");
                return "statusbar " + mode;
            }
            case "wake": {
                PowerManager pm = (PowerManager) app.getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock wl = pm.newWakeLock(
                        PowerManager.FULL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP, "agent:wake");
                wl.acquire(300);
                wl.release();
                return "wake";
            }
            case "wait": {
                int ms = Math.max(200, Math.min(8000, a.optInt("ms", 1500)));
                sleep(ms);
                return "wait " + ms + "ms";
            }
            default:
                throw new Exception("unknown action " + kind);
        }
    }

    /** 解析点击目标：优先 index → 节点中心；px/py 比例坐标；否则 x/y。 */
    private int[] resolvePoint(AgentA11yService svc, JSONObject a, List<NodeInfo> currentNodes) throws Exception {
        if (a.has("index")) {
            int idx = a.optInt("index");
            // 优先从本轮传入的 currentNodes 匹配
            if (currentNodes != null) {
                for (NodeInfo n : currentNodes) {
                    if (n.index == idx) return new int[]{n.cx, n.cy};
                }
            }
            // 兜底再次收集尝试
            List<NodeInfo> fresh = svc.collectNodes();
            if (fresh != null) {
                for (NodeInfo n : fresh) {
                    if (n.index == idx) return new int[]{n.cx, n.cy};
                }
            }
            throw new Exception("节点编号 " + idx + " 不在当前节点表中（共 " + (fresh != null ? fresh.size() : 0) + " 个）");
        }
        if (a.has("px") || a.has("py")) {
            android.graphics.Rect wb = svc.getSystemService(android.view.WindowManager.class)
                    .getCurrentWindowMetrics().getBounds();
            return new int[]{ (int) (a.optDouble("px", 0.5) * wb.width()),
                              (int) (a.optDouble("py", 0.5) * wb.height()) };
        }
        return new int[]{a.optInt("x"), a.optInt("y")};
    }

    /** 阻塞等待用户回答（5 分钟超时；等待期间用户可随时停止任务）。 */
    private String askUser(String question) {
        askLatch = new java.util.concurrent.CountDownLatch(1);
        askAnswer = null;
        Listener l = listener;
        if (l != null) l.onAsk(question);
        try {
            long deadline = System.currentTimeMillis() + 5 * 60_000;
            while (!stopRequested && System.currentTimeMillis() < deadline) {
                if (askLatch.await(400, java.util.concurrent.TimeUnit.MILLISECONDS)) break;
            }
        } catch (InterruptedException ignored) { }
        if (stopRequested) return "（用户停止了任务）";
        return askAnswer != null && !askAnswer.isEmpty() ? askAnswer : "（用户未回答或超时）";
    }

    /** UI 层提交回答。 */
    public void answerAsk(String answer) {
        askAnswer = answer;
        if (askLatch != null) askLatch.countDown();
    }

    private volatile java.util.concurrent.CountDownLatch askLatch;
    private volatile String askAnswer;

    /** 键盘无关的通用注入：写剪贴板 + 长按目标输入框，模型下一步点「粘贴」。 */
    private boolean injectViaClipboard(AgentA11yService svc, JSONObject a, String text) {
        try {
            android.app.AppOpsManager aom =
                    (android.app.AppOpsManager) app.getSystemService(android.content.Context.APP_OPS_SERVICE);
            int opMode;
            try {
                opMode = aom.unsafeCheckOpNoThrow("android:write_clipboard",
                        android.os.Process.myUid(), app.getPackageName());
            } catch (Throwable t) {
                opMode = 0;   // 查询失败不挡路，交给 setPrimaryClip 实测
            }
            if (aom != null && opMode != android.app.AppOpsManager.MODE_ALLOWED) {
                return false;   // ColorOS 剪贴板保护拒绝写入
            }
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) app.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm == null) return false;
            cm.setPrimaryClip(android.content.ClipData.newPlainText("agent", text));
            int[] xy = null;
            try { xy = resolvePoint(svc, a, null); } catch (Exception ignored) { }
            if (RootShell.available(app) && !text.equals(rootPasteDone)) {
                if (xy != null) { svc.tap(xy[0], xy[1]); sleep(350); }
                if (RootShell.paste()) {
                    rootPasteDone = text;   // 同文重复请求说明没粘上，下次走菜单
                    return true;
                }
            }
            clipPending = true;
            if (xy != null) svc.longPress(xy[0], xy[1], 900);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 缝合键盘注入：不在用则自动借用（任务结束归还）。未安装返回 false。 */
    private boolean commitViaMergedIme(String text, boolean replace) {
        try {
            app.getPackageManager().getPackageInfo("dev.patrickgold.florisboard", 0);
        } catch (Exception e) {
            return false;
        }
        String def = Settings.Secure.getString(app.getContentResolver(),
                Settings.Secure.DEFAULT_INPUT_METHOD);
        if (def == null || !def.startsWith("dev.patrickgold.florisboard/")) {
            // 自动借用缝合键盘，任务结束归还用户原输入法
            if (borrowedIme == null) borrowedIme = def;
            boolean ok = Settings.Secure.putString(app.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD,
                    "dev.patrickgold.florisboard/.agent.AgentImeBridge");
            log("自动借用缝合键盘（任务结束归还 " + borrowedIme + "）");
            if (!ok) return false;
            sleep(900);   // 等系统解绑旧 IME、绑定桥（receiver 才注册）
        }
        try {
            android.content.Intent it = new android.content.Intent("com.dsh.agent.IME_COMMIT")
                    .setPackage("dev.patrickgold.florisboard")
                    .putExtra("text", text)
                    .putExtra("replace", replace);
            app.sendBroadcast(it);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 归还借用的输入法。 */
    private void restoreIme() {
        if (borrowedIme == null) return;
        try {
            Settings.Secure.putString(app.getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD, borrowedIme);
            log("已归还输入法: " + borrowedIme);
        } catch (Exception ignored) { }
        borrowedIme = null;
    }


    /** 截图平均亮度（抽样），黑屏检测 + 诊断日志用。 */
    private static int meanLuma(Bitmap bmp) {
        if (bmp == null) return 0;
        int w = bmp.getWidth(), h = bmp.getHeight();
        long sum = 0; int n = 0;
        for (int y = 0; y < h; y += 40) {
            for (int x = 0; x < w; x += 40) {
                int c = bmp.getPixel(x, y);
                sum += ((c >> 16) & 0xff) * 0.299 + ((c >> 8) & 0xff) * 0.587 + (c & 0xff) * 0.114;
                n++;
            }
        }
        return n == 0 ? 0 : (int) (sum / n);
    }

    private void startApp(String pkg) throws Exception {
        if (!pkg.contains(".")) {   // 模型给了中文名 → 查表
            String mapped = APP_NAMES.get(pkg);
            if (mapped != null) pkg = mapped;
        }
        startAppResolved(pkg);
    }

    private void startAppResolved(String pkg) throws Exception {

        if (tryStartApp(pkg)) return;
        String[] aliases = APP_ALIASES.get(pkg);
        if (aliases != null) {
            for (String alt : aliases) {
                if (tryStartApp(alt)) return;
            }
        }
        throw new Exception("应用未安装或无启动入口: " + pkg);
    }

    private boolean tryStartApp(String pkg) {
        PackageManager pm = app.getPackageManager();
        Intent it = pm.getLaunchIntentForPackage(pkg);
        if (it == null) return false;
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            app.startActivity(it);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    private void startActivity(Intent it) throws Exception {
        it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            app.startActivity(it);
        } catch (Exception e) {
            throw new Exception("启动失败: " + e.getMessage());
        }
    }

    /** Bitmap → 降采样 JPEG data URL。 */
    public static String bmpToDataUrl(Bitmap bmp, int maxWidth, int quality) {
        if (bmp == null) return null;
        try {
            Bitmap scaled = bmp;
            if (bmp.getWidth() > maxWidth) {
                float ratio = (float) maxWidth / bmp.getWidth();
                scaled = Bitmap.createScaledBitmap(bmp, maxWidth,
                        Math.round(bmp.getHeight() * ratio), true);
            }
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, quality, buf);
            return "data:image/jpeg;base64," +
                    Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) {
            return null;
        }
    }
}
