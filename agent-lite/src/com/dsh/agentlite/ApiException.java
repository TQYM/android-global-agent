// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite;

/**
 * 服务端返回非 2xx 时抛出的异常。
 *
 * 保留 HTTP 状态码与服务端约定的 error 字段（account_exists / bad_credentials /
 * code_invalid / device_not_bound …），让 UI 只需一处映射就能给出可读中文提示，
 * 而不是把原始响应体直接抛给用户。
 */
public class ApiException extends Exception {

    public final int httpCode;
    /** 服务端 error 字段；响应不是标准错误体时为空串。 */
    public final String kind;
    /** 服务端 message 字段（或原始响应体截断），仅用于日志与兜底展示。 */
    public final String detail;

    public ApiException(int httpCode, String kind, String detail) {
        super("HTTP " + httpCode + (kind.isEmpty() ? "" : " " + kind) + " " + detail);
        this.httpCode = httpCode;
        this.kind = kind == null ? "" : kind;
        this.detail = detail == null ? "" : detail;
    }

    /** 是否为「令牌无效/缺失」类失败：调用方据此把登录状态标记为失效并引导重新登录。 */
    public boolean isAuthFailure() {
        return httpCode == 401;
    }

    /** 是否为「设备未绑定到当前账号」：重新调 /device/bind 即可自愈。 */
    public boolean isDeviceUnbound() {
        return httpCode == 403 || "device_not_bound".equals(kind);
    }

    /** 可读中文提示（UI 直接展示）。 */
    public String readable() {
        switch (kind) {
            case "account_exists":   return "账号名已被占用";
            case "bad_credentials":  return "账号名或账号密钥错误";
            case "bad_input":        return detail.isEmpty() ? "请求参数不合法" : detail;
            case "bad_payload":      return detail.isEmpty() ? "设备信息不合法" : detail;
            case "missing_token":    return "尚未登录，请先登录账号";
            case "invalid_token":    return "登录状态已失效，请重新登录";
            case "device_not_bound": return "本机未绑定到当前账号，正在重新绑定";
            case "code_invalid":     return "兑换码无效或已被使用";
            case "rate_limited":     return "操作过于频繁，请稍后再试";
            case "body_too_large":   return "上行数据过大，已跳过本次同步";
            default: break;
        }
        if (httpCode == 401) return "账号名或账号密钥错误";
        if (httpCode == 429) return "操作过于频繁，请稍后再试";
        if (httpCode >= 500) return "服务端错误（HTTP " + httpCode + "）";
        return "请求失败（HTTP " + httpCode + "）" + (detail.isEmpty() ? "" : "：" + detail);
    }
}
