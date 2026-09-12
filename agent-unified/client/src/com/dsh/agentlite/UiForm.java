// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

import android.content.Context;
import android.content.res.Configuration;
import android.util.DisplayMetrics;

/**
 * 交互形态判定与安全边距工具（手机 / 手表两套形态共用一套规则）。
 *
 * 判定依据与 MainActivity 原实现完全一致（三档任一命中即视为手表），
 * 抽出来是为了让登录页、设置页与主页选择同一套内边距，不会出现
 * 「主页按圆屏内缩、二级页按手机铺满」的形态漂移。
 */
public final class UiForm {

    private UiForm() { }

    /**
     * 手表形态判定：
     *  1) UI_MODE_TYPE_WATCH（标准 Wear 声明）；
     *  2) isScreenRound()（圆形表盘，手机无此形态）；
     *  3) 屏幕最小边 < 320dp 兜底（OWW261 466px@320dpi = 233dp；手机最小边 ≥ 320dp）。
     * 判定异常时回退手机形态（不崩）。
     */
    public static boolean isWatch(Context c) {
        if (c == null) return false;
        try {
            Configuration cfg = c.getResources().getConfiguration();
            if ((cfg.uiMode & Configuration.UI_MODE_TYPE_MASK)
                    == Configuration.UI_MODE_TYPE_WATCH) return true;
            if (cfg.isScreenRound()) return true;
            DisplayMetrics dm = c.getResources().getDisplayMetrics();
            if (dm.density > 0) {
                int minDp = (int) (Math.min(dm.widthPixels, dm.heightPixels) / dm.density);
                return minDp > 0 && minDp < 320;
            }
        } catch (Throwable t) { }
        return false;
    }

    /** 手表圆形屏的水平安全边距（values-round 下为 24dp，方形表 8dp）。 */
    public static int watchEdgePx(Context c) {
        return (int) c.getResources().getDimension(R.dimen.watch_edge);
    }
}
