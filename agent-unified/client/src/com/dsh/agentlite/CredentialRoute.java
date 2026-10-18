// SPDX-License-Identifier: Apache-2.0
package com.dsh.agentlite;

import java.net.URI;

/**
 * 任务模型的不可变凭据快照：主页预检与请求工厂共用，不联网、不改偏好。
 * 未存过 use_own_key 的旧配置保留「有 Key 直连，否则账号」；显式选择绝不兜底。
 * 可用仅表示配置齐备，不代表远端鉴权、余额或模型能力已验证。
 */
public final class CredentialRoute {
    public enum Channel { OWN_KEY, ACCOUNT }
    public enum Reason {
        READY, OWN_KEY_MISSING, OWN_URL_INVALID, LOGIN_REQUIRED, LOGIN_EXPIRED,
        GATEWAY_DISABLED, GATEWAY_URL_INVALID, MODEL_MISSING
    }

    public final Channel channel;
    public final Reason reason;
    public final String baseUrl;
    public final String model;
    private final String credential;

    private CredentialRoute(Channel channel, Reason reason, String baseUrl,
                            String credential, String model) {
        this.channel = channel;
        this.reason = reason;
        this.baseUrl = baseUrl;
        this.credential = credential;
        this.model = model;
    }

    public static CredentialRoute resolve(boolean hasChoice, boolean useOwnKey, boolean useGateway,
            String ownBase, String ownKey, String model, String accountId, String accountToken,
            boolean accountInvalid, String gatewayBase) {
        String key = clean(ownKey), token = clean(accountToken), useModel = clean(model);
        Channel channel = (hasChoice ? useOwnKey : !key.isEmpty()) ? Channel.OWN_KEY : Channel.ACCOUNT;
        Reason reason;
        String base;
        if (channel == Channel.OWN_KEY) {
            base = cleanBase(ownBase);
            reason = key.isEmpty() ? Reason.OWN_KEY_MISSING
                    : !validBase(base) ? Reason.OWN_URL_INVALID : Reason.READY;
        } else {
            base = cleanBase(gatewayBase);
            reason = !useGateway ? Reason.GATEWAY_DISABLED
                    : accountInvalid ? Reason.LOGIN_EXPIRED
                    : token.isEmpty() || clean(accountId).isEmpty() ? Reason.LOGIN_REQUIRED
                    : !validBase(base) ? Reason.GATEWAY_URL_INVALID : Reason.READY;
            base += "/v1";
        }
        if (reason == Reason.READY && useModel.isEmpty()) reason = Reason.MODEL_MISSING;
        // 拒绝态不携带可发送的地址或凭据，避免上层误用失败结果。
        return new CredentialRoute(channel, reason, reason == Reason.READY ? base : "",
                reason == Reason.READY ? (channel == Channel.OWN_KEY ? key : token) : "", useModel);
    }

    public boolean isUsable() { return reason == Reason.READY; }

    /** 仅供请求工厂消费，禁止输出到日志或界面。 */
    String credential() { return credential; }

    public String payerLabel() { return channel == Channel.OWN_KEY ? "自己的配置" : "账号额度"; }

    public String userMessage() {
        switch (reason) {
            case READY: return "使用" + payerLabel() + "；失败时不会自动更换额度来源。";
            case OWN_KEY_MISSING: return "你选择了自己的配置，但还没有填好密钥。请打开设置填写，或主动选择账号额度。";
            case OWN_URL_INVALID: return "自己的服务地址不完整，请打开设置检查；不会自动改用账号额度。";
            case LOGIN_REQUIRED: return "使用账号额度需要先登录，不需要填写密钥。";
            case LOGIN_EXPIRED: return "登录已失效，请重新登录；不会自动改用自己的配置。";
            case GATEWAY_DISABLED: return "账号通道已关闭，请主动选择账号额度重新启用，或选择自己的配置。";
            case GATEWAY_URL_INVALID: return "账号服务地址未配置好，请打开设置检查或联系服务提供方。";
            default: return "模型配置不完整，请打开设置检查。";
        }
    }

    private static String clean(String s) { return s == null ? "" : s.trim(); }
    private static String cleanBase(String s) { return clean(s).replaceAll("/+$", ""); }
    private static boolean validBase(String s) {
        try {
            URI u = new URI(s);
            return ("https".equalsIgnoreCase(u.getScheme()) || "http".equalsIgnoreCase(u.getScheme()))
                    && u.getHost() != null && !u.getHost().isEmpty()
                    && u.getRawUserInfo() == null && u.getRawQuery() == null && u.getRawFragment() == null;
        } catch (Exception ignored) { return false; }
    }

    /** 防止无意记录整个快照时泄漏凭据、地址或用户模型配置。 */
    @Override public String toString() { return "CredentialRoute{" + channel + "," + reason + "}"; }
}
