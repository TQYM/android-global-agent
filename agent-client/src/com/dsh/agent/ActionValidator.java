// SPDX-License-Identifier: Apache-2.0

package com.dsh.agent;

/**
 * 模型输出 → root 命令行的「能力网关」（2026-09-13 体检 P0-1 修复）。
 *
 * <p>背景：沙盒分支把模型返回的 package/url/action 经 {@link SandboxController} 拼进
 * {@code su -c} 命令串。模型输出可被页面内容提示注入（prompt injection），
 * {@code launchApp}/{@code reclaimTask} 的包名未加引号、{@code findTaskId} 的
 * grep 模式可被单引号截断 —— 恶意值能以 root 身份执行任意命令。
 *
 * <p>本类是这些值进入 shell 前的**唯一硬边界**：白名单校验，不匹配即抛
 * {@link IllegalArgumentException}，由引擎把错误回灌给模型（换动作重试），
 * 绝不让原始文本落到命令行上。{@link AgentEngine} 在动作入口先校验（早失败、
 * 错误信息友好），{@link SandboxController} 在 root 边界二次强制（纵深防御）。
 */
final class ActionValidator {

    private ActionValidator() { }

    /**
     * 包名白名单：字母/数字/下划线段，至少一个点分隔（如 com.tencent.mm）。
     * 该字符集不含任何 shell 元字符，可安全进入引号外或 grep 模式内。
     */
    static String pkg(String pkg) {
        if (pkg == null || !pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+")) {
            throw new IllegalArgumentException(
                    "非法包名「" + pkg + "」：只允许 xxx.yyy.zzz 形式（字母/数字/下划线 + 点分隔），"
                            + "请改用正规包名或 done 报告用户");
        }
        return pkg;
    }

    /**
     * URL 白名单：scheme 限定 http(s)/alipay/weixin/tel/mailto；其余部分排除
     * 单引号、双引号、反引号、反斜杠、空白与控制字符（$ ; & * 等在单引号内
     * 本就是字面量，保留以兼容正常查询串）。校验后仍以单引号包裹传递。
     */
    static String url(String url) {
        if (url == null
                || !url.matches("^(https?|alipay|weixin|tel|mailto):[A-Za-z0-9\\-._~/?#\\[\\]@!$&*+,;=%-]+")) {
            throw new IllegalArgumentException(
                    "非法链接「" + (url == null ? "" : url) + "」：仅支持 http(s)/alipay/weixin/tel/mailto 开头，"
                            + "且不含引号、反斜杠或空白字符");
        }
        return url;
    }

    /** Intent action 白名单：Java 包名风格（如 android.settings.WIFI_SETTINGS），用于内部映射表的二次校验。 */
    static String action(String action) {
        if (action == null || !action.matches("[A-Za-z][A-Za-z0-9_.]*")) {
            throw new IllegalArgumentException("非法 intent action「" + action + "」");
        }
        return action;
    }
}
