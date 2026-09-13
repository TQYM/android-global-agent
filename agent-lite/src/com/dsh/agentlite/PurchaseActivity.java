// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 充值页：跳百炼官方购买 → 回来自动创建 API Key。
 *
 * <p>设计见 `docs/PURCHASE_REDIRECT_DESIGN.zh-CN.md`。对外表现是
 * **「前台只跳出付款界面，其余全自动」**，用户唯一的手动步骤是在**官方页面**点确认支付。
 *
 * <p><b>三条不可协商的红线</b>（本类逐条落实）：
 * <ol>
 *   <li><b>支付必须由用户本人在官方页完成。</b>不得用 WebView 注入脚本自动勾选/自动点确认——
 *       既违反阿里云服务条款，也违反项目红线（`software-interaction-logic` §7：支付扣费前
 *       必须取得用户明示许可）。所以这里**只打开浏览器**，把付款那一下留给用户。</li>
 *   <li><b>AccessKey 绝不离开设备。</b>只写 {@link AkStore}（Android Keystore 加密），
 *       不上传、不入日志、不进崩溃报告。Secret 用完即抹。</li>
 *   <li><b>不猜「用户付款了没」。</b>靠 {@link PurchaseFlow} 比对官方侧的真实差异，
 *       而不是 `onResume` 时间窗——否则「切出去看了眼微信再回来」会被误判为已付款。</li>
 * </ol>
 *
 * <p>原来的兑换码/下单/订单记录 UI 已按决策移除（`PURCHASE_REDIRECT_DESIGN` §1 决策 4：
 * 本地隐藏充值 API；服务端那套代码保留，另一条模式仍可用）。
 */
public final class PurchaseActivity extends Activity {

    /** 大语言模型推理资源包（token 资源包）的官方购买页商品码。 */
    private static final String BUY_URL =
            "https://common-buy.aliyun.com/?commodityCode=sfm_llminference_spn_public_cn";

    private LinearLayout packsContainer;
    private TextView tvQuota, tvPayHint, tvCurrentOrder, tvOrders, tvMsg, tvAkLabel;
    private EditText etCode, etAkId;
    private Switch swSingleModel;

    private Prefs prefs;
    private AkStore akStore;

    /** 进入官方购买页前的 Key 快照；非空表示"正等用户回来自查"。 */
    private PurchaseFlow.Snapshot pendingSnapshot;
    /** 防止 onResume 重入导致重复创建 Key。 */
    private boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_purchase);
        prefs = new Prefs(this);
        akStore = new AkStore(this);

        if (UiForm.isWatch(this)) {
            int edge = UiForm.watchEdgePx(this);
            LinearLayout content = findViewById(R.id.purchaseContent);
            if (content != null) {
                content.setPadding(edge, content.getPaddingTop(), edge, content.getPaddingBottom());
            }
        }

        packsContainer = findViewById(R.id.packsContainer);
        tvQuota = findViewById(R.id.tvQuota);
        tvPayHint = findViewById(R.id.tvPayHint);
        tvCurrentOrder = findViewById(R.id.tvCurrentOrder);
        tvOrders = findViewById(R.id.tvOrders);
        tvMsg = findViewById(R.id.tvMsg);
        etCode = findViewById(R.id.etCode);
        etAkId = findViewById(R.id.etAkId);
        tvAkLabel = findViewById(R.id.tvAkLabel);
        swSingleModel = findViewById(R.id.swSingleModelBuy);

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPay).setOnClickListener(v -> openOfficialBuyPage());
        findViewById(R.id.btnCancel).setOnClickListener(v -> onSecondaryAction());

        // 旧的订单记录区块（兑换码时代）与本流程无关，隐藏而非删布局（保留可回退）
        if (tvOrders != null) tvOrders.setVisibility(View.GONE);

        swSingleModel.setOnCheckedChangeListener((btn, checked) -> {
            prefs.setSingleModelMode(checked);
            renderAkSection();
            toast(checked ? "已开启单模型模式" : "已关闭单模型模式");
        });

        renderAkSection();
        renderPacks();
        showOrderArea();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshQuota();
        // 从官方购买页返回 → 用快照比对官方侧是否真的新增了 Key（不猜时间窗）
        if (pendingSnapshot != null && !busy) {
            checkAfterPurchase();
        }
    }

    // ------------------------------------------------------------------
    // 套餐区（卡片只用于"去官方页购买"，App 内不再下单）
    // ------------------------------------------------------------------

    private void renderPacks() {
        tvPayHint.setText("购买方式：在百炼官方页面自行选择 token 资源包档位并完成支付。"
                + "\n⚠ 资源包按模型名严格匹配（如 qwen-plus 资源包不抵扣 qwen-max），"
                + "单模型模式下 App 只用一个模型，才能被同一个资源包抵扣。");

        packsContainer.removeAllViews();
        packsContainer.addView(infoCard("选择要在官方页面购买的档位",
                "App 内不代收费用，也不会替你选择档位——官方页面无法用链接预选套餐，"
                        + "请在那里自行确认价格与用量后支付。"));

        String[] names = {"体验包 · 约 ￥5", "标准包 · 约 ￥18", "专业包 · 约 ￥48"};
        String[] notes = {"适合先试一小段", "日常使用", "重度使用"};
        for (int i = 0; i < names.length; i++) {
            final String name = names[i];
            packsContainer.addView(buyCard(name, notes[i]));
        }
    }

    private View infoCard(String title, String detail) {
        LinearLayout card = card();
        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(Color.parseColor("#E2E8F0"));
        t.setTextSize(15f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(t);
        TextView d = new TextView(this);
        d.setText(detail);
        d.setTextColor(Color.parseColor("#94A3B8"));
        d.setTextSize(13f);
        d.setPadding(0, dp(4), 0, 0);
        card.addView(d);
        return card;
    }

    private View buyCard(String name, String note) {
        LinearLayout card = card();
        TextView t = new TextView(this);
        t.setText(name);
        t.setTextColor(Color.parseColor("#E2E8F0"));
        t.setTextSize(16f);
        t.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(t);

        TextView d = new TextView(this);
        d.setText(note + "　·　点击后跳到百炼官方购买页");
        d.setTextColor(Color.parseColor("#94A3B8"));
        d.setTextSize(13f);
        d.setPadding(0, dp(4), 0, dp(8));
        card.addView(d);

        Button buy = new Button(this);
        buy.setText("去官方页面购买");
        buy.setOnClickListener(v -> openOfficialBuyPage());
        card.addView(buy);
        return card;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#111C33"));
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), Color.parseColor("#1E293B"));
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(8);
        card.setLayoutParams(lp);
        return card;
    }

    // ------------------------------------------------------------------
    // AccessKey 区
    // ------------------------------------------------------------------

    private void renderAkSection() {
        swSingleModel.setChecked(prefs.singleModelMode());
        boolean hasAk = akStore.has();
        tvAkLabel.setText(hasAk
                ? "已配置 AccessKey：" + maskAkId(akStore.akId()) + "（仅存本机，Keystore 加密）"
                : "阿里云 AccessKey（仅存本机，用于自动创建 API Key）");
        etAkId.setText(hasAk ? akStore.akId() : "");
        etAkId.setEnabled(!hasAk);
        etAkId.setAlpha(hasAk ? 0.5f : 1.0f);
        etCode.setText("");
        etCode.setHint(hasAk
                ? "AccessKeySecret（已保存；要更换请先点下方「更换 AccessKey」）"
                : "AccessKeySecret（只在本机加密保存，绝不上传）");
        etCode.setEnabled(!hasAk);
        etCode.setAlpha(hasAk ? 0.5f : 1.0f);
        ((Button) findViewById(R.id.btnCancel)).setText(hasAk ? "更换 AccessKey" : "保存 AccessKey");
    }

    /** 只显示前 6 位与后 4 位——AccessKeyId 本身不是秘密，但也没必要整条展示。 */
    private static String maskAkId(String id) {
        if (id == null || id.length() <= 12) return id == null ? "" : id;
        return id.substring(0, 6) + "……" + id.substring(id.length() - 4);
    }

    /** 次级按钮：未配置时=保存 AccessKey；已配置时=清除后重新录入。 */
    private void onSecondaryAction() {
        if (akStore.has()) {
            akStore.clear();
            renderAkSection();
            msg("已清除本地 AccessKey，请重新录入", false);
            return;
        }
        String id = etAkId.getText().toString().trim();
        char[] secret = etCode.getText().toString().trim().toCharArray();
        if (id.isEmpty() || secret.length == 0) {
            msg("请填写 AccessKeyId 与 AccessKeySecret", true);
            return;
        }
        boolean ok = akStore.save(id, secret);
        AkStore.wipe(secret);
        etCode.setText("");
        if (!ok) {
            msg("保存失败：本机加密不可用，未写入任何明文", true);
            return;
        }
        renderAkSection();
        msg("AccessKey 已加密保存到本机。建议用 RAM 子账号，且只授予 modelstudio:CreateApiKey。", false);
    }

    // ------------------------------------------------------------------
    // 主流程：打开官方购买页 → 回来比对 → 自动创建
    // ------------------------------------------------------------------

    private void openOfficialBuyPage() {
        if (!akStore.has()) {
            msg("请先填写并保存 AccessKey，否则购买后无法自动创建 API Key", true);
            return;
        }
        // 打开购买页**之前**取快照：这是判定"是否真的新增了 Key"的唯一基线
        msg("正在记录当前 Key 状态…", false);
        BailianClient.list(this, new BailianClient.ListCallback() {
            @Override public void onOk(List<PurchaseFlow.KeyInfo> keys) {
                pendingSnapshot = new PurchaseFlow.Snapshot(keys, System.currentTimeMillis());
                launchBuyUrl();
            }
            @Override public void onErr(String message, boolean retryable) {
                // 拿不到基线就**不能**推进：没有基线时的"新增判定"是无意义的
                msg("无法读取当前 Key 列表（" + message + "）。"
                        + (retryable ? "请检查网络后重试。" : "请先在设置页确认 AccessKey 有效。"), true);
            }
        });
    }

    private void launchBuyUrl() {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(BUY_URL));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            msg("已打开百炼官方购买页：请自行选择档位并完成支付，付款后返回本页。", false);
        } catch (Throwable t) {
            msg("无法打开浏览器，请手动访问：" + BUY_URL, true);
        }
    }

    /** 回到本页后：查官方侧是否真的多了 Key。 */
    private void checkAfterPurchase() {
        busy = true;
        msg("正在确认购买结果…", false);
        BailianClient.list(this, new BailianClient.ListCallback() {
            @Override public void onOk(List<PurchaseFlow.KeyInfo> keys) {
                PurchaseFlow.Decision d = PurchaseFlow.evaluate(pendingSnapshot, keys);
                switch (d.verdict) {
                    case NEW_KEY_FOUND:
                        pendingSnapshot = null;
                        msg("已确认官方侧新增了 API Key，正在启用…", false);
                        // 列表接口拿不到 Key 明文（只有 id/时间/描述），所以这里显式创建一把，
                        // 语义明确、结果可预期；代价是账号里会多一把 Key，属可接受取舍。
                        createKey();
                        break;
                    case NO_BASELINE:
                        busy = false;
                        pendingSnapshot = null;   // 没有基线，只能重来一轮
                        msg(d.reason, true);
                        break;
                    default:
                        // 没新增：**不推进**。可能还没付款，也可能刚付完尚未落账。
                        busy = false;
                        msg(d.reason + "（如果你已付款，请稍等几秒后点「重新检查」）", false);
                        break;
                }
            }
            @Override public void onErr(String message, boolean retryable) {
                // 保留快照：用户可点「重新检查」再比对一次，不必重开购买页
                busy = false;
                msg("确认购买结果失败：" + message + (retryable ? "（可点「重新检查」再试）" : ""), true);
            }
        });
    }

    /**
     * 调 CreateApiKey → **校验 → 才写入** → 置位官方购买模式。
     *
     * <p>顺序是刻意的：**校验在写入之前**。若先写 Prefs 再校验，一旦 Key 不可用，
     * 客户端就停在一个最坏的中间态——标记为官方购买、网关已让路、但 Key 是废的，
     * 既不能走我方额度也不能直连。宁可什么都不写。
     */
    private void createKey() {
        msg("正在创建 API Key…", false);
        BailianClient.create(this, null, new BailianClient.Callback() {
            @Override public void onOk(BailianClient.CreatedKey key) {
                // ⚠️ 成功判定已在 BailianClient 内写死：必须 Success==true 且 Key 非空
                validateThenCommit(key.apiKeyValue);
            }
            @Override public void onErr(String message, boolean retryable) {
                busy = false;
                msg("创建 API Key 失败：" + message
                        + (retryable ? "（可重试）" : "（需先修正配置）"), true);
            }
        });
    }

    /** 写入前先发一次真实请求验证这把 Key 确实可用。 */
    private void validateThenCommit(final String newKey) {
        msg("正在校验新 Key 是否可用…", false);
        final String base = prefs.baseUrl();
        final String model = prefs.model();
        new Thread(() -> {
            String err = validateKey(base, newKey, model);
            runOnUiThread(() -> {
                busy = false;
                if (err == null) {
                    prefs.setApiKey(newKey);
                    prefs.setUseOwnKey(true);      // 网关让路：用户用自己的额度
                    msg("完成：API Key 已创建并验证可用，已切换到官方购买模式"
                            + "（使用你自己的百炼额度）。", false);
                    toast("API Key 已启用");
                    renderAkSection();
                    showOrderArea();
                } else {
                    // 回滚：**不写 Prefs、不置位 useOwnKey**，保持在改动前的可用状态
                    msg("新 Key 校验未通过，已放弃启用（未改动现有配置）：" + err
                            + "\n可点「去官方页面购买」重试，或在设置页用「更改 API」手动填写。", true);
                }
            });
        }, "bailian-validate-key").start();
    }

    /**
     * 用一把 key 发一次最小请求，验证它真的能用。
     *
     * @return null = 可用；非 null = 面向用户的失败原因（**已按 T-4 去掉堆栈/类名**）
     */
    static String validateKey(String baseUrl, String apiKey, String model) {
        try {
            LlmClient c = new LlmClient(baseUrl, apiKey, model);
            org.json.JSONArray msgs = new org.json.JSONArray()
                    .put(LlmClient.textMsg("user", "ping"));
            String out = c.chat(msgs);
            if (out == null || out.trim().isEmpty()) return "模型返回了空响应";
            return null;
        } catch (Throwable t) {
            // 不把异常原文抛给用户：可能是带 URL/密钥片段的 JVM 英文串
            return UiText.uiReason(String.valueOf(t.getMessage()));
        }
    }

    // ------------------------------------------------------------------
    // 状态区
    // ------------------------------------------------------------------

    private void showOrderArea() {
        tvCurrentOrder.setText("当前使用的 API Key：" + describeCurrentKey());
    }

    private String describeCurrentKey() {
        String k = prefs.apiKey();
        if (k == null || k.isEmpty()) return "未配置（走我方额度网关或未登录）";
        return maskAkId(k) + "（" + (prefs.singleModelMode() ? "单模型模式" : "多模型模式") + "）";
    }

    private void refreshQuota() {
        if (!prefs.loggedIn()) {
            tvQuota.setText("未登录 —— 官方购买模式不依赖账号登录");
            return;
        }
        long left = Math.max(0, prefs.accountQuota() - prefs.accountQuotaUsed());
        tvQuota.setText("我方额度：" + left + " units（官方购买模式下不使用）");
    }

    private void msg(String text, boolean error) {
        tvMsg.setText(text);
        tvMsg.setTextColor(error ? Color.parseColor("#F87171") : Color.parseColor("#94A3B8"));
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
