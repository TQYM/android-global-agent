package com.dsh.agentlite;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.LinearInterpolator;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * 代理可见性与底栏灵动胶囊控制器（零 root、免悬浮窗权限）：
 * 全部窗口经无障碍服务的 TYPE_ACCESSIBILITY_OVERLAY 挂载。
 *
 * 核心升级：
 * 1. 灵动岛下移至底部沉浸式手势栏上方（Bottom Dynamic Island）：
 *    - 彻底规避不同厂商顶栏挖孔（左侧/中置/药丸/双摄）、时间电池状态遮挡、Letterboxing 兼容性问题。
 *    - 悬浮在底部手势指示条上方（y = 28dp），手指点按更符合单手交互习惯。
 * 2. 自动贴合全屏幕真实曲面轮廓的 ScreenGlowView，杜绝预设直角长方形。
 * 3. 接入 Android 12+ 莫奈动态色彩（MonetColors），全局视觉一体化。
 * 4. 全局清除 Emoji，采用严谨原生风格纯净字元与标识符。
 */
public class OverlayController {

    private final Context svc;          // AccessibilityService 上下文（建窗前提）
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final WindowManager wm;

    private ScreenGlowView glowView;    // 全屏曲面自适应贴合呼吸灯
    private ValueAnimator breath;
    private ValueAnimator dotPulse;

    private LinearLayout capsule;       // 底部灵动胶囊
    private TextView tvIslandDot;       // 胶囊左侧指示点
    private TextView tvCapsule;         // 胶囊实时文案
    private ProgressBar islandSpin;     // 胶囊右侧微型进度环

    private View panel;                 // 展开控制面板
    private TextView tvPanelStatus;
    private Button btnTakeover;
    private EditText etSupp;
    private FrameLayout cardBox;        // 强制接管/授权卡片窗
    private boolean cardShowing;

    private String currentTask;
    private boolean taskActive;
    private boolean paused;

    public OverlayController(Context serviceCtx) {
        svc = serviceCtx;
        wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                svc.getResources().getDisplayMetrics());
    }

    /** 构造底部沉浸窗口参数 */
    private WindowManager.LayoutParams lpBottom(int w, int h, int flags) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        p.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        return p;
    }

    /** 全屏背景参数 */
    private WindowManager.LayoutParams lpFull(int flags) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        p.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS;
        return p;
    }

    private static final int FLAGS_PASS = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
    private static final int FLAGS_TOUCH = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;
    private static final int FLAGS_FOCUS = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL;

    // ================= 任务生命周期 =================

    /** 任务开始：底部胶囊 + 曲面贴合呼吸灯上线。 */
    public void taskStarted(String task) {
        ui.post(() -> {
            try {
                currentTask = task;
                taskActive = true;
                paused = false;
                ensureGlow();
                ensureCapsule();
                setGlowMode(false);
                startBreath();
                setCapsuleState(true, "正在理解任务…");
                AgentEngine.staticLog("overlay: 底部灵动胶囊+曲面贴合呼吸灯已上线");
            } catch (Throwable t) {
                AgentEngine.staticLog("overlay taskStarted 失败: " + t);
            }
        });
    }

    /** 进展文案（每步动作后更新底部胶囊）。 */
    public void progress(String text) {
        ui.post(() -> {
            if (taskActive) setCapsuleState(true, text);
        });
    }

    /** 任务结束：胶囊/面板/光晕退场；resultShown=false 时连卡片一起撤。 */
    public void taskEnded(boolean keepCard) {
        ui.post(() -> {
            taskActive = false;
            paused = false;
            stopBreath();
            stopDotPulse();
            removeView(glowView); glowView = null;
            removeView(panel); panel = null;
            if (!keepCard) {
                removeView(capsule); capsule = null;
                hideCardSync();
            } else {
                // 若保留结果，让胶囊保持成功态 4 秒后淡出
                ui.postDelayed(() -> {
                    removeView(capsule); capsule = null;
                }, 4000);
            }
        });
    }

    /** 全部撤下（服务断开时调用）。 */
    public void hideAll() {
        ui.post(() -> {
            taskActive = false;
            stopBreath();
            stopDotPulse();
            removeView(glowView); glowView = null;
            removeView(capsule); capsule = null;
            removeView(panel); panel = null;
            hideCardSync();
        });
    }

    // ================= 接管 / 暂停 =================

    /** 进入接管态：呼吸灯切为素色细轮廓（任务未终止），胶囊改暂停文案。 */
    public void setPaused(String reason) {
        ui.post(() -> {
            paused = true;
            setGlowMode(true);
            stopBreath();
            setCapsulePaused(reason == null ? "已接管 · 任务暂停" : "请手动操作 · 任务暂停");
            if (btnTakeover != null) btnTakeover.setText("交还 AI");
        });
    }

    /** 退出接管态：曲面呼吸灯恢复。 */
    public void clearPaused() {
        ui.post(() -> {
            paused = false;
            if (taskActive) {
                setGlowMode(false);
                startBreath();
                setCapsuleState(true, "AI 继续执行…");
            }
            if (btnTakeover != null) btnTakeover.setText("暂停接管");
        });
    }

    // ================= 卡片（接管 / 授权 / 终态） =================

    /** 强制接管卡片：白底圆角 + 感叹号标识 + 「请手动操作」+ 莫奈强调按钮 + 补充/停止常驻。 */
    public void showTakeover(String scenario) {
        ui.post(() -> {
            LinearLayout box = cardShell();
            box.addView(cardHead(0xFFF85149, "!", "请手动操作"));
            box.addView(cardText(scenario + "。完成后点击下方按钮，把主动权交还 AI。"));
            box.addView(monetButton("我已完成，交还 AI", v -> {
                AgentEngine.get(svc).requestResume();
            }));
            box.addView(bottomRow(true));
        });
    }

    /** 事前授权卡片：单次允许（主）/ 始终允许（次）/ 拒绝（文本）三选项。 */
    public void showAuth(String appLabel) {
        ui.post(() -> {
            LinearLayout box = cardShell();
            box.addView(cardHead(MonetColors.primary(svc), "A", "授权请求"));
            box.addView(cardText("AI 助手想要操作「" + appLabel
                    + "」。\n「始终允许」后同类操作不再打断（可随时在主界面「授权」中撤回）。"));
            box.addView(monetButton("单次允许", v -> AgentEngine.get(svc).submitAuth(0)));
            Button always = new Button(svc);
            always.setText("始终允许");
            always.setTextColor(0xFF3A3F4B);
            always.setBackgroundResource(R.drawable.btn_ghost);
            box.addView(always, vMargin());
            always.setOnClickListener(v -> AgentEngine.get(svc).submitAuth(1));
            Button deny = new Button(svc);
            deny.setText("拒绝");
            deny.setTextColor(0xFF8B93A3);
            deny.setBackground(null);
            box.addView(deny, vMargin());
            deny.setOnClickListener(v -> AgentEngine.get(svc).submitAuth(2));
        });
    }

    /** 绿色完成卡片：一句话结论 + 关键产物。 */
    public void showDone(String summary) {
        ui.post(() -> {
            try {
                setCapsuleDone("完成: " + summary);
                LinearLayout box = cardShell();
                box.addView(cardHead(0xFF10B981, "OK", "任务已完成"));
                box.addView(cardText(summary));
                box.addView(monetButton("知道了", v -> hideCardSync()));
                AgentEngine.staticLog("overlay: 完成卡片与灵动胶囊已就绪");
            } catch (Throwable t) {
                AgentEngine.staticLog("overlay showDone 失败: " + t);
            }
        });
    }

    /** 灵动岛 Agent 提问交互卡片：直接悬浮前台，提供输入框/快速确认/跳过 */
    public void showAsk(String question) {
        ui.post(() -> {
            try {
                setCapsulePaused("Agent 提问中 · 等待回答");
                LinearLayout box = cardShell();
                box.addView(cardHead(MonetColors.primary(svc), "?", "Agent 提问"));
                box.addView(cardText(question));

                // 输入框
                final EditText etAnswer = new EditText(svc);
                etAnswer.setHint("输入回答，或直接点击下方选项…");
                etAnswer.setHintTextColor(0xFF9E9E9E);
                etAnswer.setTextColor(0xFF1B1F27);
                etAnswer.setTextSize(14);
                etAnswer.setBackgroundResource(R.drawable.bg_input);
                etAnswer.setPadding(dp(14), dp(10), dp(14), dp(10));
                etAnswer.setSingleLine(true);
                LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
                etLp.topMargin = dp(12);
                box.addView(etAnswer, etLp);

                // 快捷操作栏（确认完成 / 是 / 否 / 提交）
                LinearLayout btnRow = new LinearLayout(svc);
                btnRow.setOrientation(LinearLayout.HORIZONTAL);
                btnRow.setGravity(Gravity.CENTER_VERTICAL);
                LinearLayout.LayoutParams btnRowLp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                btnRowLp.topMargin = dp(12);

                // 快捷回答「已完成/好」
                Button btnOk = new Button(svc);
                btnOk.setText("已确认/已完成");
                btnOk.setTextSize(13);
                btnOk.setTextColor(0xFFFFFFFF);
                btnOk.setTypeface(null, android.graphics.Typeface.BOLD);
                btnOk.setBackgroundResource(R.drawable.btn_primary);
                LinearLayout.LayoutParams okLp = new LinearLayout.LayoutParams(0, dp(42), 1.3f);
                okLp.rightMargin = dp(8);
                btnRow.addView(btnOk, okLp);

                // 提交自定义输入
                Button btnSubmit = new Button(svc);
                btnSubmit.setText("发送输入");
                btnSubmit.setTextSize(13);
                btnSubmit.setTextColor(0xFF3A3F4B);
                btnSubmit.setBackgroundResource(R.drawable.btn_ghost);
                LinearLayout.LayoutParams submitLp = new LinearLayout.LayoutParams(0, dp(42), 1.0f);
                submitLp.rightMargin = dp(8);
                btnRow.addView(btnSubmit, submitLp);

                // 跳过
                Button btnSkip = new Button(svc);
                btnSkip.setText("跳过");
                btnSkip.setTextSize(13);
                btnSkip.setTextColor(0xFF8B93A3);
                btnSkip.setBackground(null);
                LinearLayout.LayoutParams skipLp = new LinearLayout.LayoutParams(0, dp(42), 0.7f);
                btnRow.addView(btnSkip, skipLp);

                box.addView(btnRow, btnRowLp);

                btnOk.setOnClickListener(v -> {
                    hideCardSync();
                    AgentEngine.get(svc).answerAsk("已确认完成，请继续下一步");
                });

                btnSubmit.setOnClickListener(v -> {
                    String ans = etAnswer.getText().toString().trim();
                    if (ans.isEmpty()) ans = "好的，请继续";
                    hideCardSync();
                    AgentEngine.get(svc).answerAsk(ans);
                });

                btnSkip.setOnClickListener(v -> {
                    hideCardSync();
                    AgentEngine.get(svc).answerAsk("");
                });

                AgentEngine.staticLog("overlay: 提问卡片已在灵动岛前台弹出");
            } catch (Throwable t) {
                AgentEngine.staticLog("overlay showAsk 失败: " + t);
            }
        });
    }

    /** 失败卡片：明示归因。 */
    public void showFail(String reason) {
        ui.post(() -> {
            try {
                setCapsuleFail("失败: " + reason);
                LinearLayout box = cardShell();
                box.addView(cardHead(0xFFEF4444, "X", "任务未完成"));
                box.addView(cardText(reason));
                box.addView(monetButton("关闭", v -> hideCardSync()));
                AgentEngine.staticLog("overlay: 失败卡片已弹出");
            } catch (Throwable t) {
                AgentEngine.staticLog("overlay showFail 失败: " + t);
            }
        });
    }

    public void hideCard() { ui.post(this::hideCardSync); }

    // ================= 内部：底栏灵动胶囊与展开面板 =================

    /** 底部常驻灵动胶囊（位于手势导航栏上方，不遮挡屏幕内容） */
    private void ensureCapsule() {
        if (capsule != null) return;
        LinearLayout box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setBackgroundResource(R.drawable.capsule_bg);
        box.setPadding(dp(13), dp(6), dp(13), dp(6));
        box.setMinimumWidth(dp(160));

        // 左侧 AI 状态呼吸点
        tvIslandDot = new TextView(svc);
        tvIslandDot.setText("●");
        tvIslandDot.setTextSize(12);
        tvIslandDot.setTextColor(MonetColors.accent(svc));
        tvIslandDot.setGravity(Gravity.CENTER);
        box.addView(tvIslandDot, new LinearLayout.LayoutParams(dp(18), dp(18)));

        // 中间实时滚动文本
        tvCapsule = new TextView(svc);
        tvCapsule.setTextColor(0xFFFFFFFF);
        tvCapsule.setTextSize(13);
        tvCapsule.setTypeface(null, android.graphics.Typeface.BOLD);
        tvCapsule.setSingleLine(true);
        tvCapsule.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        tvCapsule.setMarqueeRepeatLimit(-1);
        tvCapsule.setSelected(true);
        tvCapsule.setMaxWidth(dp(220));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.leftMargin = dp(8);
        tlp.rightMargin = dp(8);
        box.addView(tvCapsule, tlp);

        // 右侧微型加载环
        islandSpin = new ProgressBar(svc, null, android.R.attr.progressBarStyleSmall);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(16), dp(16));
        box.addView(islandSpin, slp);

        // 点按胶囊展开控制面板
        box.setOnClickListener(v -> togglePanel());

        capsule = box;
        WindowManager.LayoutParams p = lpBottom(WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT, FLAGS_TOUCH);

        // 放置于屏幕底部，避让手势条（设置 y = 28dp 悬浮于底部手势条上方）
        p.y = dp(28);
        wm.addView(capsule, p);

        startDotPulse();
    }

    private void setCapsuleState(boolean running, String text) {
        if (tvCapsule != null) tvCapsule.setText(text);
        if (tvPanelStatus != null) tvPanelStatus.setText(text);
        if (tvIslandDot != null) {
            tvIslandDot.setText("●");
            tvIslandDot.setTextColor(MonetColors.accent(svc));
        }
        if (islandSpin != null) islandSpin.setVisibility(View.VISIBLE);
    }

    private void setCapsulePaused(String text) {
        if (tvCapsule != null) tvCapsule.setText(text);
        if (tvPanelStatus != null) tvPanelStatus.setText(text);
        if (tvIslandDot != null) {
            tvIslandDot.setText("||");
            tvIslandDot.setTextColor(0xFFFBBF24);
        }
        if (islandSpin != null) islandSpin.setVisibility(View.GONE);
    }

    private void setCapsuleDone(String text) {
        if (tvCapsule != null) tvCapsule.setText(text);
        if (tvPanelStatus != null) tvPanelStatus.setText(text);
        if (tvIslandDot != null) {
            tvIslandDot.setText("OK");
            tvIslandDot.setTextColor(0xFF34D399);
        }
        if (islandSpin != null) islandSpin.setVisibility(View.GONE);
        stopDotPulse();
    }

    private void setCapsuleFail(String text) {
        if (tvCapsule != null) tvCapsule.setText(text);
        if (tvPanelStatus != null) tvPanelStatus.setText(text);
        if (tvIslandDot != null) {
            tvIslandDot.setText("X");
            tvIslandDot.setTextColor(0xFFEF4444);
        }
        if (islandSpin != null) islandSpin.setVisibility(View.GONE);
        stopDotPulse();
    }

    private void startDotPulse() {
        stopDotPulse();
        if (tvIslandDot == null) return;
        dotPulse = ValueAnimator.ofFloat(0.85f, 1.15f);
        dotPulse.setDuration(1200);
        dotPulse.setRepeatMode(ValueAnimator.REVERSE);
        dotPulse.setRepeatCount(ValueAnimator.INFINITE);
        dotPulse.addUpdateListener(a -> {
            if (tvIslandDot != null) {
                float val = (float) a.getAnimatedValue();
                tvIslandDot.setScaleX(val);
                tvIslandDot.setScaleY(val);
            }
        });
        dotPulse.start();
    }

    private void stopDotPulse() {
        if (dotPulse != null) { dotPulse.cancel(); dotPulse = null; }
    }

    /** 展开控制面板（从底部平滑弹出） */
    private void togglePanel() {
        if (panel != null) { removeView(panel); panel = null; return; }
        LinearLayout box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.panel_bg);
        box.setPadding(dp(16), dp(14), dp(16), dp(14));

        // 顶栏：标题 + 收起按钮
        LinearLayout header = new LinearLayout(svc);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView tvHead = new TextView(svc);
        tvHead.setText("Agent · 实时控制面板");
        tvHead.setTextColor(0xFFFFFFFF);
        tvHead.setTextSize(14);
        tvHead.setTypeface(null, android.graphics.Typeface.BOLD);
        header.addView(tvHead, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button btnClose = new Button(svc);
        btnClose.setText("X");
        btnClose.setTextColor(0xFF94A3B8);
        btnClose.setTextSize(14);
        btnClose.setBackground(null);
        btnClose.setOnClickListener(v -> togglePanel());
        header.addView(btnClose, new LinearLayout.LayoutParams(dp(36), dp(36)));
        box.addView(header);

        // 任务目标卡片
        if (currentTask != null && !currentTask.isEmpty()) {
            TextView tvTarget = new TextView(svc);
            tvTarget.setText("任务：" + currentTask);
            tvTarget.setTextColor(0xFF94A3B8);
            tvTarget.setTextSize(12);
            tvTarget.setPadding(dp(10), dp(6), dp(10), dp(6));
            tvTarget.setBackgroundResource(R.drawable.bg_card);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            tlp.topMargin = dp(8);
            box.addView(tvTarget, tlp);
        }

        // 当前实时动作展示
        tvPanelStatus = new TextView(svc);
        tvPanelStatus.setTextColor(0xFFF1F5F9);
        tvPanelStatus.setTextSize(13);
        tvPanelStatus.setTypeface(null, android.graphics.Typeface.BOLD);
        tvPanelStatus.setText(tvCapsule == null ? "AI 正在执行…" : tvCapsule.getText());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(8);
        box.addView(tvPanelStatus, slp);

        // 补充通道：追加指令在线修正，不打断任务
        LinearLayout suppRow = new LinearLayout(svc);
        suppRow.setOrientation(LinearLayout.HORIZONTAL);
        suppRow.setGravity(Gravity.CENTER_VERTICAL);
        etSupp = new EditText(svc);
        etSupp.setHint("补充指令，例如：只要上午的车次");
        etSupp.setTextColor(0xFFF1F5F9);
        etSupp.setHintTextColor(0xFF64748B);
        etSupp.setTextSize(13);
        etSupp.setSingleLine(true);
        etSupp.setBackgroundResource(R.drawable.bg_input);
        etSupp.setPadding(dp(12), 0, dp(12), 0);
        suppRow.addView(etSupp, new LinearLayout.LayoutParams(0, dp(42), 1));

        Button send = new Button(svc);
        send.setText("发送");
        send.setTextSize(13);
        send.setTextColor(0xFFFFFFFF);
        send.setBackgroundResource(R.drawable.btn_primary);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(42));
        blp.leftMargin = dp(8);
        suppRow.addView(send, blp);
        send.setOnClickListener(v -> {
            String t = etSupp.getText().toString().trim();
            if (t.isEmpty()) return;
            etSupp.setText("");
            AgentEngine.get(svc).submitSupplement(t);
            tvPanelStatus.setText("已补充指令：" + t);
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        box.addView(suppRow, rlp);

        // 控制行：接管/交还 + 停止 + 收起
        LinearLayout ctl = new LinearLayout(svc);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        btnTakeover = new Button(svc);
        btnTakeover.setText(paused ? "交还 AI" : "暂停接管");
        btnTakeover.setTextSize(12);
        btnTakeover.setTextColor(0xFFFFFFFF);
        btnTakeover.setBackgroundResource(R.drawable.btn_chip);
        btnTakeover.setOnClickListener(v -> {
            if (paused) AgentEngine.get(svc).requestResume();
            else AgentEngine.get(svc).requestTakeover();
        });
        ctl.addView(btnTakeover, new LinearLayout.LayoutParams(0, dp(40), 1));

        Button stop = new Button(svc);
        stop.setText("停止任务");
        stop.setTextSize(12);
        stop.setTextColor(0xFFF87171);
        stop.setBackgroundResource(R.drawable.btn_ghost);
        LinearLayout.LayoutParams slp2 = new LinearLayout.LayoutParams(0, dp(40), 1);
        slp2.leftMargin = dp(8);
        ctl.addView(stop, slp2);
        stop.setOnClickListener(v -> AgentEngine.get(svc).stop());

        Button collapse = new Button(svc);
        collapse.setText("收起");
        collapse.setTextSize(12);
        collapse.setTextColor(0xFF94A3B8);
        collapse.setBackgroundResource(R.drawable.btn_ghost);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                dp(68), dp(40));
        clp.leftMargin = dp(8);
        ctl.addView(collapse, clp);
        collapse.setOnClickListener(v -> togglePanel());

        LinearLayout.LayoutParams clpAll = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clpAll.topMargin = dp(10);
        box.addView(ctl, clpAll);

        panel = box;
        WindowManager.LayoutParams p = lpBottom(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT, FLAGS_FOCUS);
        p.y = dp(76);
        p.width = svc.getResources().getDisplayMetrics().widthPixels - dp(24);
        wm.addView(panel, p);
    }

    // ================= 内部：曲面贴合呼吸灯 =================

    private void ensureGlow() {
        if (glowView != null) return;
        glowView = new ScreenGlowView(svc);
        wm.addView(glowView, lpFull(FLAGS_PASS));
    }

    private void setGlowMode(boolean borderOnly) {
        if (glowView != null) {
            glowView.setPausedMode(borderOnly);
        }
    }

    /** 呼吸式明暗动画（1.8s 周期），动态曲面贴合全屏。 */
    private void startBreath() {
        stopBreath();
        if (glowView == null) return;
        breath = ValueAnimator.ofFloat(0.35f, 1f);
        breath.setDuration(1800);
        breath.setRepeatMode(ValueAnimator.REVERSE);
        breath.setRepeatCount(ValueAnimator.INFINITE);
        breath.setInterpolator(new LinearInterpolator());
        breath.addUpdateListener(a -> {
            if (glowView != null) glowView.setBreathFactor((float) a.getAnimatedValue());
        });
        breath.start();
    }

    private void stopBreath() {
        if (breath != null) { breath.cancel(); breath = null; }
    }

    // ================= 内部：卡片构件 =================

    private LinearLayout cardShell() {
        ensureCardBox();
        cardBox.removeAllViews();
        LinearLayout box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackgroundResource(R.drawable.card_white);
        box.setPadding(dp(20), dp(16), dp(20), dp(14));
        cardBox.addView(box, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        cardShowing = true;
        return box;
    }

    private void ensureCardBox() {
        if (cardBox != null) { cardShowing = true; return; }
        cardBox = new FrameLayout(svc);
        WindowManager.LayoutParams p = lpBottom(WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT, FLAGS_TOUCH);
        p.width = svc.getResources().getDisplayMetrics().widthPixels - dp(32);
        p.y = dp(24);
        wm.addView(cardBox, p);
        cardShowing = true;
    }

    private void hideCardSync() {
        if (!cardShowing) return;
        cardShowing = false;
        removeView(cardBox);
        cardBox = null;
    }

    /** 卡片头：标识字符 + 标题。 */
    private View cardHead(int color, String glyph, String title) {
        LinearLayout row = new LinearLayout(svc);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView icon = new TextView(svc);
        icon.setText(glyph);
        icon.setTextColor(0xFFFFFFFF);
        icon.setTextSize(14);
        icon.setTypeface(null, android.graphics.Typeface.BOLD);
        icon.setGravity(Gravity.CENTER);
        android.graphics.drawable.GradientDrawable circle =
                new android.graphics.drawable.GradientDrawable();
        circle.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        circle.setColor(color);
        icon.setBackground(circle);
        row.addView(icon, new LinearLayout.LayoutParams(dp(34), dp(34)));

        TextView tv = new TextView(svc);
        tv.setText(title);
        tv.setTextColor(0xFF1B1F27);
        tv.setTextSize(17);
        tv.setTypeface(null, android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = dp(12);
        row.addView(tv, lp);
        return row;
    }

    private View cardText(String s) {
        TextView tv = new TextView(svc);
        tv.setText(s);
        tv.setTextColor(0xFF3A3F4B);
        tv.setTextSize(14);
        tv.setLineSpacing(0, 1.2f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        tv.setLayoutParams(lp);
        return tv;
    }

    private Button monetButton(String text, View.OnClickListener l) {
        Button b = new Button(svc);
        b.setText(text);
        b.setTextColor(0xFFFFFFFF);
        b.setTextSize(15);
        b.setTypeface(null, android.graphics.Typeface.BOLD);
        b.setBackgroundResource(R.drawable.btn_primary);
        b.setLayoutParams(vMargin());
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams vMargin() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(12);
        return lp;
    }

    /** 底部常驻「补充 / 停止」双出口。 */
    private View bottomRow(boolean withSupplement) {
        LinearLayout row = new LinearLayout(svc);
        row.setOrientation(LinearLayout.HORIZONTAL);
        if (withSupplement) {
            Button supp = new Button(svc);
            supp.setText("补充");
            supp.setTextColor(0xFF3A3F4B);
            supp.setBackground(null);
            supp.setOnClickListener(v -> {
                AgentEngine.get(svc).requestResume();
                ui.postDelayed(this::ensureCapsule, 300);
                ui.postDelayed(() -> { if (capsule != null && panel == null) togglePanel(); }, 600);
            });
            row.addView(supp, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        }
        Button stop = new Button(svc);
        stop.setText("停止");
        stop.setTextColor(0xFFF85149);
        stop.setBackground(null);
        stop.setOnClickListener(v -> AgentEngine.get(svc).stop());
        row.addView(stop, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(4);
        row.setLayoutParams(lp);
        return row;
    }

    // ================= 工具 =================

    private void removeView(View v) {
        if (v == null) return;
        try { wm.removeView(v); } catch (Exception ignored) { }
    }
}
