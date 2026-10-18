// SPDX-License-Identifier: Apache-2.0
package com.dsh.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 三功能端到端测试夹具：对话框主题的「广告」浮层，浮在 AgentLite 上但不遮挡其底部按钮。
 * 点「跳过广告」后立即 finish。注意：不设 contentDescription——NodeInfo.label() 会把
 * text 与 desc 拼接，重复文本会让 PopupGuard 的精确匹配失效（这正是被测行为的一部分）。
 */
public final class PopupFixtureActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(48, 40, 48, 48);

        TextView title = new TextView(this);
        title.setText("测试广告覆盖层");
        title.setTextSize(22);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, 0, 0, 32);
        root.addView(title, new LinearLayout.LayoutParams(-2, -2));

        Button skip = new Button(this);
        skip.setText("跳过广告");
        skip.setOnClickListener(v -> finish());
        root.addView(skip, new LinearLayout.LayoutParams(-2, -2));

        setContentView(root);
    }
}
