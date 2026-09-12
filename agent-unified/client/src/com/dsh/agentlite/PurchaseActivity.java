// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

/**
 * 收款界面（应用内买单页）：套餐展示 → 下单 → 支付 → 订单记录。
 *
 * **支付方式的现状必须说清楚**：真实在线支付（支付宝/微信）需要支付商户资质，
 * 尚未接入。服务端在 `/api/v1/packs` 里用 `online_pay=false` 明确告知，
 * 本页据此把这一点直接写在界面上，而不是做一个点了没反应的「立即支付」按钮。
 * 当前唯一可用的支付方式是**兑换码**——它走的是已经闭环的核销+记账链路。
 *
 * 记账口径：额度以兑换码实际额度为准（服务端保证），订单记录所选套餐供对账。
 */
public final class PurchaseActivity extends Activity {

    private static final String TAG = "AgentLiteBuy";

    private LinearLayout packsContainer;
    private TextView tvQuota, tvPayHint, tvCurrentOrder, tvOrders, tvMsg;
    private EditText etCode;

    private String currentOrderId;
    private final java.util.List<AccountClient.Pack> packs = new java.util.ArrayList<>();

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        setContentView(R.layout.activity_purchase);

        // 圆屏手表：内容收进内接方形（与 LoginActivity/SettingsActivity 同处理）
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

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnPay).setOnClickListener(v -> doPay());
        findViewById(R.id.btnCancel).setOnClickListener(v -> doCancel());

        refreshQuota();
        loadShop();
        loadOrders();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshQuota();
    }

    private void refreshQuota() {
        Prefs p = new Prefs(this);
        if (!p.loggedIn()) {
            tvQuota.setText("未登录 —— 请先在设置页登录账号");
            return;
        }
        long left = Math.max(0, p.accountQuota() - p.accountQuotaUsed());
        tvQuota.setText("当前额度：" + left + " units（1 unit = 1000 tokens）");
    }

    private void loadShop() {
        AccountClient.shop(this, new AccountClient.ApiCallback<AccountClient.Shop>() {
            @Override public void onOk(AccountClient.Shop sh) {
                packs.clear();
                packs.addAll(sh.packs);
                renderPacks(sh);
            }
            @Override public void onErr(String message) {
                tvPayHint.setText("套餐读取失败：" + message);
            }
        });
    }

    private void renderPacks(AccountClient.Shop sh) {
        if (sh.onlinePay) {
            tvPayHint.setText("支付方式：" + String.join(" / ", sh.payMethods));
        } else {
            tvPayHint.setText("⚠ 在线支付尚未接入（需支付商户资质）。"
                    + "当前可用支付方式：兑换码 —— 选择套餐下单，再用兑换码完成支付。");
        }
        packsContainer.removeAllViews();
        for (AccountClient.Pack pk : packs) {
            packsContainer.addView(packCard(pk));
        }
    }

    private View packCard(AccountClient.Pack pk) {
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

        TextView title = new TextView(this);
        title.setText(pk.name + "　￥" + trimNum(pk.priceCny));
        title.setTextColor(Color.parseColor("#E2E8F0"));
        title.setTextSize(16f);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        card.addView(title);

        TextView detail = new TextView(this);
        long total = pk.totalUnits();
        String s = total + " units（基础 " + pk.units + (pk.bonus > 0 ? " + 赠送 " + pk.bonus : "") + "）";
        if (pk.note != null && !pk.note.isEmpty()) s += "　·　" + pk.note;
        detail.setText(s);
        detail.setTextColor(Color.parseColor("#94A3B8"));
        detail.setTextSize(13f);
        detail.setPadding(0, dp(4), 0, dp(8));
        card.addView(detail);

        Button buy = new Button(this);
        buy.setText("选择此套餐");
        buy.setOnClickListener(v -> doCreateOrder(pk));
        card.addView(buy);
        return card;
    }

    private void doCreateOrder(AccountClient.Pack pk) {
        msg("正在下单…", false);
        AccountClient.createOrder(this, pk.packId, new AccountClient.ApiCallback<AccountClient.Order>() {
            @Override public void onOk(AccountClient.Order o) {
                currentOrderId = o.orderId;
                tvCurrentOrder.setText("订单 " + o.orderId + "\n" + o.packName
                        + "　应付 ￥" + trimNum(o.priceCny) + "　应得 " + o.units + " units\n"
                        + "状态：" + statusZh(o.status) + "（15 分钟内有效，逾期作废）");
                msg("已下单，请在下方输入兑换码完成支付", false);
                loadOrders();
            }
            @Override public void onErr(String message) { msg("下单失败：" + message, true); }
        });
    }

    private void doPay() {
        if (currentOrderId == null || currentOrderId.isEmpty()) {
            msg("请先在上方选择一个套餐下单", true);
            return;
        }
        String code = etCode.getText().toString();
        msg("正在核销兑换码…", false);
        AccountClient.payOrder(this, currentOrderId, code, new AccountClient.ApiCallback<AccountClient.Order>() {
            @Override public void onOk(AccountClient.Order o) {
                etCode.setText("");
                Toast.makeText(PurchaseActivity.this,
                        "支付成功，到账 " + o.units + " units", Toast.LENGTH_LONG).show();
                tvCurrentOrder.setText("订单 " + o.orderId + " 已支付（" + o.units + " units 已到账）");
                currentOrderId = null;
                refreshQuota();
                loadOrders();
                msg("", false);
            }
            @Override public void onErr(String message) { msg("支付失败：" + message, true); }
        });
    }

    private void doCancel() {
        if (currentOrderId == null || currentOrderId.isEmpty()) { msg("没有待取消的订单", true); return; }
        final String id = currentOrderId;
        AccountClient.cancelOrder(this, id, new AccountClient.ApiCallback<AccountClient.Order>() {
            @Override public void onOk(AccountClient.Order o) {
                currentOrderId = null;
                tvCurrentOrder.setText("订单 " + id + " 已取消");
                loadOrders();
            }
            @Override public void onErr(String message) { msg("取消失败：" + message, true); }
        });
    }

    private void loadOrders() {
        AccountClient.orders(this, new AccountClient.ApiCallback<List<AccountClient.Order>>() {
            @Override public void onOk(List<AccountClient.Order> list) {
                if (list.isEmpty()) { tvOrders.setText("暂无订单"); return; }
                StringBuilder sb = new StringBuilder();
                for (AccountClient.Order o : list) {
                    sb.append(o.packName).append("　").append(o.units).append(" units　￥")
                      .append(trimNum(o.priceCny)).append("　").append(statusZh(o.status));
                    if (o.method != null && !o.method.isEmpty()) sb.append("（").append(o.method).append("）");
                    sb.append("\n").append(o.orderId).append("　").append(shortTime(o.createdAt)).append("\n\n");
                }
                tvOrders.setText(sb.toString().trim());
                refreshQuota();
            }
            @Override public void onErr(String message) { tvOrders.setText("订单读取失败：" + message); }
        });
    }

    private static String statusZh(String s) {
        if (s == null) return "未知";
        switch (s) {
            case "pending": return "待支付";
            case "paid": return "已支付";
            case "cancelled": return "已取消";
            case "expired": return "已过期";
            default: return s;
        }
    }

    private static String shortTime(String iso) {
        if (iso == null || iso.length() < 16) return iso == null ? "" : iso;
        return iso.substring(0, 10) + " " + iso.substring(11, 16);
    }

    private static String trimNum(double d) {
        if (d == Math.floor(d)) return String.valueOf((long) d);
        return String.format(java.util.Locale.US, "%.2f", d);
    }

    private void msg(String text, boolean error) {
        tvMsg.setText(text);
        tvMsg.setTextColor(error ? Color.parseColor("#F87171") : Color.parseColor("#94A3B8"));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
