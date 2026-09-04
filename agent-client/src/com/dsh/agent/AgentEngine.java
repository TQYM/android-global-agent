package com.dsh.agent;

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

    private AgentEngine(Context ctx) { app = ctx; }

    public boolean isRunning() { return running; }

    public void setListener(Listener l) { listener = l; }

    private void log(String s) {
        Log.i(TAG, s);
        Listener l = listener;
        if (l != null) l.onLog(s);
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
        KeepAliveService.start(app);
        thread = new Thread(() -> {
            try {
                runLoop(task);
            } catch (Throwable t) {
                log("任务异常终止: " + t);
            } finally {
                running = false;
                status();
            }
        }, "agent-loop");
        thread.start();
        return true;
    }

    public void stop() {
        stopRequested = true;
        log("收到停止请求");
    }

    // ---- 动作 schema（小布/小爱式：直达优先，界面兜底） ----
    private static final String SCHEMA = "\n\n动作必须是单个 JSON 对象，字段 action 取值：\n" +
            "【直达能力 —— 优先使用，像系统语音助手一样一步到位】\n" +
            "- {\"action\":\"setting\",\"page\":\"wifi\"}             直达设置页(可选: wifi bluetooth display sound apps notifications location security battery storage date language accessibility airplane network vpn nfc cast developer deviceinfo home)\n" +
            "- {\"action\":\"app\",\"package\":\"包名\"}               启动应用(打不开时换 tap 桌面图标)\n" +
            "- {\"action\":\"open_url\",\"url\":\"...\"}              打开链接或应用 scheme(如 https://、alipay://、weixin://、tel:10086)\n" +
            "- {\"action\":\"wifi\"} / {\"action\":\"bluetooth\"}      打开 WiFi/蓝牙开关面板(系统弹出面板上可直接开关)\n" +
            "- {\"action\":\"brightness\",\"level\":<0-255>}        直接调亮度(首次需授予修改系统设置权限)\n" +
            "- {\"action\":\"volume\",\"dir\":\"up|down|mute\"}       音量\n" +
            "- {\"action\":\"statusbar\",\"mode\":\"notifications|settings\"}  展开通知栏/快捷设置\n" +
            "- {\"action\":\"wake\"}                              点亮屏幕\n" +
            "【界面操作 —— 直达做不到时的兜底】\n" +
            "- {\"action\":\"tap\",\"index\":<节点编号>}            点击节点（首选编号；目标不在表中才用 \"x\",\"y\" 坐标）\n" +
            "- {\"action\":\"tap\",\"px\":0.50,\"py\":0.42}         比例坐标点击(px,py 为 0~1 的屏幕宽/高比例，从截图估计；仅节点表为空时使用，swipe 同理可用 px1,py1,px2,py2)\n" +
            "- {\"action\":\"longpress\",\"index\":<节点编号>}      长按节点(可带 \"dur\" 毫秒)\n" +
            "- {\"action\":\"swipe\",\"x1\":<int>,\"y1\":<int>,\"x2\":<int>,\"y2\":<int>,\"dur\":<int>} 滑动\n" +
            "- {\"action\":\"scroll\",\"direction\":\"up\"|\"down\"}    翻页\n" +
            "- {\"action\":\"key\",\"code\":<int>}                  按键(4=返回,3=主页,187=最近任务)\n" +
            "- {\"action\":\"edge_back\",\"side\":\"left|right\"}    边缘手势返回(从屏幕左/右边缘向内滑，全面屏手势的「返回」)\n" +
            "- {\"action\":\"text\",\"text\":\"...\"}                 输入文字(支持中文，替换输入框内容；先 tap 聚焦输入框)\n" +
            "- {\"action\":\"wait\",\"ms\":<int>}                   等待页面加载(最长 8000ms)\n" +
            "- {\"action\":\"done\",\"summary\":\"完成说明\"}          任务已完成\n" +
            "原则：能直达不翻页；页面在加载先 wait；弹窗/广告优先点关闭/跳过；同一动作执行后屏幕没变化必须换策略，不要重复点同一位置。\n" +
            "返回上级界面有三条路，按顺序尝试，一条没反应立刻换下一条：① key 4 系统返回；② tap 节点表里的「返回/←/back」节点（通常在屏幕左上角，坐标 x 很小、y 在顶部）；③ edge_back 边缘手势返回。\n" +
            "应用内部的设置页/详情页/聊天页/个人主页等都是该应用的一部分——页面跳转了不代表离开了应用，不要因此返回或重启；判断标准是任务进展。\n" +
            "节点数为 0 或屏幕全黑 = 应用正在加载（启动页/开屏广告），必须先 wait 2000~3000ms，绝对不要按 key 3/key 4/edge_back——那会把刚打开的应用退掉。开屏广告出现「跳过」节点时 tap 它。\n" +
            "只输出 JSON，不要输出任何其他文字、解释或 markdown 代码块。";

    // OEM 包名别名（ColorOS/一加实测）
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
        LlmClient llm = new LlmClient(prefs.baseUrl(), prefs.apiKey(), prefs.model());
        int maxSteps = prefs.maxSteps();
        boolean vision = prefs.vision();

        log("任务启动: " + task + " (model=" + prefs.model() + (vision ? " +vision" : "") + ")");
        status();

        JSONArray messages = new JSONArray();
        messages.put(LlmClient.textMsg("system", prefs.systemPrompt() + SCHEMA));
        messages.put(LlmClient.textMsg("user", "任务：" + task));

        String lastKey = "";
        int repeats = 0;
        java.util.List<String> fpHistory = new java.util.ArrayList<>();
        int shotFails = 0;
        int zeroNodeSteps = 0;
        boolean visionOn = vision;
        lastApp = null;
        visionOnly = false;
        boolean prevFailed = false;

        for (step = 1; step <= maxSteps; step++) {
            if (stopRequested) { log("任务已被用户停止"); return; }

            AgentA11yService svc = AgentA11yService.get();
            if (svc == null) { log("无障碍服务断开，任务中止"); return; }

            List<NodeInfo> nodes = svc.collectNodes();
            if (nodes.isEmpty()) {   // 加载中的空屏不值得问模型
                sleep(1500);
                nodes = svc.collectNodes();
            }
            zeroNodeSteps = nodes.isEmpty() ? zeroNodeSteps + 1 : 0;
            log("第 " + step + " 步：感知到 " + nodes.size() + " 个节点" +
                    (zeroNodeSteps >= 2 ? "（连续空节点，疑似应用屏蔽无障碍）" : ""));
            String appHint = lastApp == null ? "" :
                    "（当前在应用 " + lastApp + " 内，其二级/三级页面都是它的一部分，不要因界面变化就返回或重启）\n";

            // 视觉：截图降采样为 ≤640px JPEG data URL；连续失败自动降级纯节点模式
            JSONObject perceive;
            String prompt;
            boolean needShot = visionOn && (visionOnly || step == 1 || nodes.size() < 5
                    || prevFailed || zeroNodeSteps >= 1);
            prevFailed = false;
            if (needShot) {
                Bitmap bmp = svc.screenshot();
                String dataUrl = bmpToDataUrl(bmp, 640, 60);
                if (dataUrl != null) {
                    shotFails = 0;
                    int lum = meanLuma(bmp);
                    log("截图亮度≈" + lum + (lum < 8 ? "（黑屏，截图可能被屏蔽）" : ""));
                    if (zeroNodeSteps >= 2 && lum >= 8) {
                        // 纯视觉模式：节点被屏蔽但像素可见 → 坐标驱动
                        if (!visionOnly) { visionOnly = true; log("进入纯视觉模式：改用比例坐标操作"); }
                        android.graphics.Rect wb = svc.getSystemService(android.view.WindowManager.class)
                                .getCurrentWindowMetrics().getBounds();
                        prompt = appHint + "该应用屏蔽了无障碍节点（节点表为空），只能看截图用比例坐标操作。" +
                                "屏幕宽=" + wb.width() + " 高=" + wb.height() + "。" +
                                "tap/longpress 用 px,py（0~1 比例），swipe 用 px1,py1,px2,py2。" +
                                "back/home/key 不受影响仍可用。仔细看截图找到目标位置再动手。";
                    } else {
                        visionOnly = false;
                        prompt = appHint + (nodes.isEmpty()
                                ? "当前屏幕没有任何可交互节点（应用正在加载或显示开屏广告）。请 wait 等待加载，或看到「跳过」就点它。"
                                : NodeInfo.toPrompt(nodes, 40));
                    }
                    perceive = LlmClient.visionMsg("user", prompt + "\n\n同时附上了当前屏幕截图。", dataUrl);
                    pushScreen(bmp);
                } else {
                    shotFails++;
                    if (shotFails >= 3) {
                        visionOn = false;
                        log("截图连续失败（系统限频），转为纯节点模式");
                    }
                    perceive = LlmClient.textMsg("user", nodes.isEmpty()
                            ? "当前屏幕没有任何可交互节点且截图失败。请 wait 后重试。"
                            : NodeInfo.toPrompt(nodes, 40));
                }
            } else {
                perceive = LlmClient.textMsg("user", appHint + (nodes.isEmpty()
                        ? "当前屏幕没有任何可交互节点（应用正在加载或显示开屏广告）。请 wait 等待加载。"
                        : NodeInfo.toPrompt(nodes, 40)));
            }
            messages.put(perceive);

            String reply;
            try {
                reply = llm.chat(messages);
            } catch (Exception e) {
                log("LLM 调用失败: " + e.getMessage());
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
            if ("done".equals(kind)) {
                String summary = action.optString("summary", "完成");
                log("任务完成：" + summary);
                return;
            }

            String execErr = null;
            String label = kind;
            try {
                label = exec(svc, action);
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

            // 自适应等待：感知树变化或超时（通常 ~0.5s，上限 1.6s）
            if (!"wait".equals(kind)) waitForChange(svc, nodes);

            // 死循环检测
            String actKey = kind + "|" + action.opt("index") + "|" +
                    action.optInt("x") + "," + action.optInt("y");
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
                    log("屏幕连续 6 步无任何变化，判定卡死，任务中止");
                    return;
                }
            }
            String nextMsg = "已执行动作，这是执行后的屏幕，请继续下一步（或 done）。";
            if (repeats >= 2) {
                nextMsg = "警告：你已连续 " + (repeats + 1) + " 次执行完全相同但没有进展的动作。" +
                        "必须换策略——用 index 点击节点表中真正的目标节点，或 scroll 翻页，不要再点同一位置。";
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
        log("达到最大步数 " + maxSteps + "，任务未确认完成");
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
        sleep(250);
        long deadline = System.currentTimeMillis() + 1200;
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested) return;
            List<NodeInfo> cur = svc.collectNodes();
            if (!NodeInfo.fingerprint(cur).equals(fp)) return;
            sleep(200);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }

    // ---- 动作执行（零 root 实现） ----

    private String exec(AgentA11yService svc, JSONObject a) throws Exception {
        String kind = a.optString("action", "");
        switch (kind) {
            case "tap": {
                int[] xy = resolvePoint(svc, a);
                pushTap(xy[0], xy[1]);
                if (!svc.tap(xy[0], xy[1])) throw new Exception("手势被系统取消");
                return a.has("index") ? "tap#" + a.optInt("index") : "tap";
            }
            case "longpress": {
                int[] xy = resolvePoint(svc, a);
                pushTap(xy[0], xy[1]);
                int dur = a.optInt("dur", 900);
                if (!svc.longPress(xy[0], xy[1], dur)) throw new Exception("长按手势被系统取消");
                return "longpress" + (a.has("index") ? "#" + a.optInt("index") : "");
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
                if (!ok) throw new Exception("滑动手势被系统取消");
                return "swipe";
            }
            case "scroll": {
                int h = app.getResources().getDisplayMetrics().heightPixels;
                int w = app.getResources().getDisplayMetrics().widthPixels;
                boolean down = "down".equals(a.optString("direction", "down"));
                // 语义与旧版一致：direction=down 表示内容向下翻（手指上滑）
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
            case "back": svc.goBack(); return "back";
            case "home": svc.goHome(); lastApp = null; return "home";
            case "text": {
                String text = a.optString("text", "");
                if (!visionOnly) {
                    String err = svc.setText(text, a.optBoolean("append", false));
                    if (err == null) return "text";
                    // ACTION_SET_TEXT 被拒 → 尝试 Agent 键盘通道
                }
                if (AgentImeService.commit(app, text, !a.optBoolean("append", false))) {
                    return "text(键盘通道)";
                }
                throw new Exception("输入失败：目标拒绝无障碍写入且未启用 Agent 键盘"
                        + "（请在系统设置-输入法中启用并切换到 Agent 键盘）");
            }
            case "app": {
                String pkg = a.optString("package", "");
                startApp(pkg);
                sleep(2000);   // 应用启动必有启动页，等它加载完再感知
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
    private int[] resolvePoint(AgentA11yService svc, JSONObject a) throws Exception {
        if (a.has("index")) {
            int idx = a.optInt("index");
            List<NodeInfo> nodes = svc.collectNodes();
            for (NodeInfo n : nodes) {
                if (n.index == idx) return new int[]{n.cx, n.cy};
            }
            throw new Exception("节点编号 " + idx + " 不在当前节点表中（共 " + nodes.size() + " 个）");
        }
        if (a.has("px") || a.has("py")) {
            android.graphics.Rect wb = svc.getSystemService(android.view.WindowManager.class)
                    .getCurrentWindowMetrics().getBounds();
            return new int[]{ (int) (a.optDouble("px", 0.5) * wb.width()),
                              (int) (a.optDouble("py", 0.5) * wb.height()) };
        }
        return new int[]{a.optInt("x"), a.optInt("y")};
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
