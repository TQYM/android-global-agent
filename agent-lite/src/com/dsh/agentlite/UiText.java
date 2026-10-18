// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

/**
 * 面向用户文案的加工与映射（守则 §9 T-4 / §11 O-3）。
 *
 * **本类刻意不引用任何 android.\* API**，因此可以在桌面 JVM 上原样编译运行——
 * 与 {@link TlsPin} 同样的取舍：把「只在真机屏幕上才看得见」的规则变成可自动验证的纯函数。
 * 验证见 `tests/ui_text_test/UiTextTest.java`（由 `tests/ui_text_guard.sh` 调用）。
 *
 * 两条规则：
 *   T-4 终态归因由模型给出、引擎**统一加工**——面向用户的文案必须不含堆栈与内部字段名；
 *   O-3 进度文案与内部标签分离——内部标签必须不直接暴露给用户。
 */
public final class UiText {

    private UiText() { }

    /** 面向用户的归因加工（T-4）。 */
    public static String uiReason(String raw) {
        String s = trim(raw == null ? "" : raw, 400);
        int nl = s.indexOf('\n');
        if (nl > 0) s = s.substring(0, nl);                              // 堆栈行不展示
        s = s.replaceAll("\\s+at\\s+[\\w.$]+\\([^)]*\\)", " ");           // at com.x.Y(Z.java:1)
        s = s.replaceAll("\\b(?:[a-z][\\w]*\\.){2,}[A-Z][\\w$]*", "");    // 全限定类名
        s = s.replaceAll("\\b[A-Z][\\w$]*Exception\\b:?", "");            // 简写异常类名
        s = s.replaceAll("\\s{2,}", " ")
             .replaceAll("^[：:，,、\\-—\\s]+", "")
             .replaceAll("[：:]\\s*[：:]+", "：")                          // 去类名后残留的重复冒号
             .replaceAll("[：:、，,]\\s*$", "")                           // 悬空尾标点
             .trim();
        // JVM 报文残留：类名被去掉后，英文内部描述（"Attempt to invoke … on a null object
        // reference"）仍会留在串里。这类内容对用户没有信息量——在标记处截断，只留前面的归因。
        for (String marker : JVM_MARKERS) {
            int i = s.indexOf(marker);
            if (i > 0) {
                s = s.substring(0, i).replaceAll("[：:，,、\\-—\\s]+$", "");
                break;
            }
        }
        if (s.isEmpty()) return "任务未能完成（可缩小任务范围后重试）";
        // 技术串判定要同时看**清洗前**：异常类名正是在清洗中消失的，只看清洗后会把
        // 「java.lang.IllegalStateException: cannot read property」误判成正常文案。
        boolean technical = technicalLooking(raw)
                || s.matches("(?i).*(exception|caused by|nullpointer|attempt to invoke|null object reference"
                             + "|cannot read|out of bounds|\\bat\\s+[\\w.$]+\\()");
        if (technical && !s.matches(".*\\p{IsHan}.*")) {
            return "任务未能完成（引擎内部异常，可重试）";
        }
        return trim(s, 120);
    }

    /**
     * 内部动作标签 → 面向用户的进度文案（O-3）。
     *
     * 覆盖 `AgentEngine.exec()` 产出的全部标签形态与 `SCHEMA` 全部动作名；
     * **兜底不回原文**——未映射的标签由调用方记 logcat，用户看到通用文案。
     *
     * @param appDisplayName `app &lt;包名&gt;` 场景下已解析好的应用显示名；为 null 时退回包名
     */
    /**
     * 失败终态的「下一步」建议（SPEC-UX-001 §4：每个失败都必须可恢复）。
     *
     * <p>只依据**已加工的人话归因**判断，不读原始异常——这样文案永远不泄露技术原文，
     * 也能保证「网络/额度/无障碍/配置/超时」这几类高频失败各自有明确出口。
     */
    public static String nextStep(String reason) {
        String r = reason == null ? "" : reason;
        if (r.contains("网络") || r.contains("连接") || r.contains("断网")) {
            return "检查手机网络（WiFi 或流量）后点「重试一次」";
        }
        if (r.contains("两条模型通道")) {
            return "稍后重试；若持续如此请联系服务方，或在「设置 → 高级」换一份可用的接口配置";
        }
        // 自带通道欠费必须排在「账号额度」分支之前：该路径的归因串本身就含「账号额度」，
        // 落到下面会建议「配置自己的接口作为备用通道」——而他正在用自己那份接口，
        // 正确出口是给自己的服务账号充值（2026-10-08 真机回归实测踩到）。
        if (r.contains("自己的模型服务额度不足")) {
            return "给自己的模型服务账号充值后点「重试一次」；也可在设置里主动切到账号额度";
        }
        if (r.contains("账号额度")) {
            return "稍后重试；或在「设置 → 高级」里配置自己的接口作为备用通道";
        }
        if (r.contains("额度") || r.contains("充值") || r.contains("余额") || r.contains("欠费")) {
            return "到「设置 → 账号与额度」领取试用额度或用兑换码充值";
        }
        if (r.contains("密钥") || r.contains("权限")) {
            return "登录账号使用账号额度；或到「设置 → 高级」更换/校正接口配置";
        }
        if (r.contains("模型")) {
            return "到「设置 → 高级」换一个可用的模型名再试";
        }
        if (r.contains("无障碍")) {
            return "在系统设置里重新开启「Agent 无障碍服务」，再点「重试一次」";
        }
        if (r.contains("配置") || r.contains("登录") || r.contains("令牌") || r.contains("凭据")) {
            return "登录账号，或在「设置 → 高级」里填写自己的接口配置";
        }
        if (r.contains("超时") || r.contains("步数") || r.contains("太久")) {
            return "把任务拆小一点（例如先只说「打开微信」）再试";
        }
        return "点「重试一次」；仍失败就把任务说得更具体一点（例如指明应用名）";
    }

    public static String friendlyLabel(String raw, String appDisplayName) {
        if (raw == null || raw.isEmpty()) return "正在执行…";
        if (raw.equals("app")) return "打开应用";   // exec 抛异常时 label=kind 的兜底路径
        if (raw.startsWith("app ")) {
            String pkg = raw.substring(4);
            return "打开应用「" + (appDisplayName == null || appDisplayName.isEmpty() ? pkg : appDisplayName) + "」";
        }
        if (raw.startsWith("open_api 降级")) return "改用应用直启（API 直达不可用）";
        if (raw.startsWith("open_api")) return "调用应用直达能力";
        if (raw.startsWith("open_url")) return "打开直达链接";
        if (raw.startsWith("tap#")) return "点击界面元素";
        if (raw.startsWith("tap")) return "点击目标位置";
        if (raw.startsWith("longpress")) return "长按界面元素";
        if (raw.startsWith("text")) return "输入文本内容";
        if (raw.startsWith("swipe") || raw.startsWith("scroll")) return "滑动浏览屏幕";
        if (raw.startsWith("edge_back")) return "边缘手势返回";
        if (raw.startsWith("key")) return "系统按键操作";
        if (raw.startsWith("back")) return "返回上一级";
        if (raw.startsWith("home")) return "回到主屏";
        if (raw.startsWith("list_apps")) return "匹配已装应用";
        if (raw.startsWith("setting")) return "打开系统设置页";
        if (raw.startsWith("wifi")) return "打开 WiFi 面板";
        if (raw.startsWith("bluetooth")) return "打开蓝牙设置";
        if (raw.startsWith("brightness")) return "调整屏幕亮度";
        if (raw.startsWith("volume")) return "调整音量";
        if (raw.startsWith("statusbar")) return "操作状态栏";
        if (raw.startsWith("wake")) return "点亮屏幕";
        if (raw.startsWith("wait")) return "等待页面加载";
        if (raw.startsWith("remote")) return "让其他设备执行任务";
        if (raw.startsWith("ask")) return "等待你的确认";
        if (raw.startsWith("done")) return "收尾任务";
        if (raw.startsWith("fail")) return "中止任务";
        return "正在执行…";   // 未映射：绝不回原文（调用方负责记 logcat）
    }

    /** 该标签是否已被映射（供调用方决定要不要记「未映射」日志）。 */
    public static boolean isMapped(String raw) {
        if (raw == null || raw.isEmpty()) return true;
        return !"正在执行…".equals(friendlyLabel(raw, null)) || "正在执行…".equals(raw);
    }

    /** JVM 报文的特征片段：命中即说明后面是给开发者看的英文内部描述。 */
    private static final String[] JVM_MARKERS = {
            "Attempt to invoke", "attempt to invoke", "null object reference",
            "Cannot read", "cannot read", "Index out of", "Out of bounds",
            "Object reference not set", "at ", "Caused by",
    };

    /** 原始串是否带有技术特征（异常类名/堆栈）——必须在清洗前判断。 */
    private static boolean technicalLooking(String raw) {
        return raw != null && raw.matches("(?i).*(exception|caused by|nullpointer|stacktrace|"
                + "\\bat\\s+[\\w.$]+\\(|\\bjava\\.[\\w.]+|\\bandroid\\.[\\w.]+).*");
    }

    private static String trim(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
