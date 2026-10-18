// SPDX-License-Identifier: Apache-2.0
package com.dsh.agentlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.widget.Toast;

/** 手机/手表共用的付费通道显式选择；保存配置不等于同意切换付费账户。 */
public final class CredentialRouteUi {
    private CredentialRouteUi() {}

    public static void showChooser(Activity activity, Prefs prefs, Runnable changed) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        CredentialRoute current = prefs.credentialRoute(prefs.model());
        new AlertDialog.Builder(activity)
                .setTitle("选择额度来源")
                .setMessage("当前：" + current.payerLabel() + "\n" + current.userMessage()
                        + "\n\n账号额度由应用账号扣减；自己的配置由对应服务账号计费。"
                        + "\n模型服务可能接收任务文字和截图，并非只在本机处理。"
                        + "\n选择对新任务生效；失败时不会自动换来源。")
                .setPositiveButton("使用账号额度", (d, w) -> {
                    prefs.selectCredentialChannel(false);
                    changed.run();
                    if (!prefs.loggedIn()) {
                        activity.startActivity(new Intent(activity, LoginActivity.class));
                    } else {
                        Toast.makeText(activity, prefs.credentialRoute(prefs.model()).userMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("使用自己的配置", (d, w) -> {
                    prefs.selectCredentialChannel(true);
                    changed.run();
                    Toast.makeText(activity, prefs.credentialRoute(prefs.model()).userMessage(),
                            Toast.LENGTH_LONG).show();
                })
                .setNeutralButton("暂不切换", null)
                .show();
    }
}
