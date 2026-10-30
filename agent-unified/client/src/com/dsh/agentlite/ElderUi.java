// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListAdapter;
import android.widget.TextView;

/**
 * 适老化界面工具（手机形态）：主题切换 + 大字号对话框 + 大字号列表。
 *
 * <p>为什么集中在这里：系统 AlertDialog 的正文 / 按钮 / 列表项字号由平台主题写死（约 14–16sp），
 * 主题属性改不动；老年用户恰恰在这些弹窗里做「清空 / 撤回 / 退出」这类关键决定。
 * 所有弹窗统一经 {@link #show} 放大，不必在每个调用点各写一遍。
 *
 * <p>手表形态一律原样返回（小屏放大会把内容挤出圆形表盘），行为与改版前完全一致。
 */
public final class ElderUi {

    private ElderUi() { }

    /** 适老化界面只在手机形态启用；手表保持原深色紧凑界面。 */
    public static boolean enabled(Context c) {
        return !UiForm.isWatch(c);
    }

    /** 在 setContentView 之前调用：手机形态切到浅色高对比主题。 */
    public static void applyTheme(Activity a) {
        if (enabled(a)) a.setTheme(R.style.Theme_AgentLite_Elder);
    }

    /** 透明召唤页（AssistActivity）用的浅色透明主题。 */
    public static void applyTranslucentTheme(Activity a) {
        if (enabled(a)) a.setTheme(R.style.Theme_AgentLite_Elder_Translucent);
    }

    /** 显示并放大对话框（标题 / 正文 / 按钮 / 自定义输入框）。 */
    public static AlertDialog show(AlertDialog.Builder b) {
        AlertDialog d = b.show();
        enlarge(d);
        return d;
    }

    /** 放大已显示的对话框。必须在 show() 之后调用：控件在 show 时才被创建。 */
    public static void enlarge(AlertDialog d) {
        if (d == null || !enabled(d.getContext())) return;
        Context c = d.getContext();
        try {
            int titleId = c.getResources().getIdentifier("alertTitle", "id", "android");
            if (titleId != 0) size(d.findViewById(titleId), R.dimen.elder_text_section);
            TextView msg = d.findViewById(android.R.id.message);
            if (msg != null) {
                size(msg, R.dimen.elder_text_body);
                msg.setTextColor(c.getColor(R.color.elder_text));
                msg.setLineSpacing(0, 1.3f);
            }
            for (int which : new int[]{AlertDialog.BUTTON_POSITIVE,
                    AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL}) {
                Button btn = d.getButton(which);
                if (btn == null) continue;
                size(btn, R.dimen.elder_text_body);
                btn.setAllCaps(false);
                btn.setMinHeight(px(c, R.dimen.elder_touch));
                btn.setTypeface(null, android.graphics.Typeface.BOLD);
                // 危险操作用红字，其余用主色——老人靠颜色区分「确定」与「清空」
                CharSequence t = btn.getText();
                boolean danger = t != null && isDangerWord(t.toString());
                btn.setTextColor(c.getColor(danger ? R.color.elder_danger : R.color.elder_primary));
            }
            enlargeEdits(d.getWindow() == null ? null : d.getWindow().getDecorView());
        } catch (Throwable ignored) {
            // 放大只是视觉增强：任何 ROM 差异导致找不到控件时，保持系统默认外观即可
        }
    }

    /**
     * 大字号列表适配器（替代 setItems）：每项 20sp、最小高度 64dp，长文案自动换行而不是截断。
     * 手表形态退回平台默认列表项布局。
     */
    public static ListAdapter list(Context c, String[] items) {
        final boolean big = enabled(c);
        return new ArrayAdapter<String>(c, android.R.layout.simple_list_item_1, items) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                if (big && v instanceof TextView) {
                    TextView tv = (TextView) v;
                    size(tv, R.dimen.elder_text_body);
                    tv.setTextColor(c.getColor(R.color.elder_text));
                    tv.setMinHeight(px(c, R.dimen.elder_touch));
                    tv.setSingleLine(false);
                    tv.setMaxLines(4);
                    int pad = px(c, R.dimen.elder_gap_s);
                    tv.setPadding(tv.getPaddingLeft(), pad, tv.getPaddingRight(), pad);
                }
                return v;
            }
        };
    }

    /** 文案里出现这些字的按钮按危险操作着色。 */
    static boolean isDangerWord(String s) {
        return s.contains("清空") || s.contains("删除") || s.contains("退出")
                || s.contains("重置") || s.contains("撤回") || s.contains("不执行");
    }

    private static void enlargeEdits(View v) {
        if (v instanceof android.widget.EditText) {
            size((TextView) v, R.dimen.elder_text_body);
            ((TextView) v).setMinHeight(px(v.getContext(), R.dimen.elder_input_min));
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) enlargeEdits(g.getChildAt(i));
        }
    }

    private static void size(View v, int dimenRes) {
        if (v instanceof TextView) {
            ((TextView) v).setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    v.getResources().getDimension(dimenRes));
        }
    }

    private static int px(Context c, int dimenRes) {
        return c.getResources().getDimensionPixelSize(dimenRes);
    }
}
