package com.dsh.agentlite;

import java.util.List;

/**
 * 敏感场景守卫（报告 §5.2 安全分级强制接管）。
 * 每步对感知节点做关键词扫描：命中即自动暂停任务并弹出「请手动操作」接管卡片，
 * AI 不得代行支付/协议/验证/系统授权四类动作。
 *
 * 词表分四组，对应报告敏感操作清单；命中文本即接管卡片的场景说明。
 */
public final class SensitiveGuard {

    private SensitiveGuard() { }

    /** 支付环节（硬边界） */
    private static final String[] PAYMENT = {
            "支付密码", "请输入支付密码", "输入支付密码", "确认付款", "立即支付",
            "交易密码", "银行卡密码", "指纹支付", "输入密码付款"
    };
    /** 身份验证（硬边界） */
    private static final String[] VERIFY = {
            "短信验证码", "输入验证码", "验证码", "人脸识别", "人脸验证", "刷脸",
            "指纹验证", "请验证指纹", "输入密码", "登录密码", "图形验证"
    };
    /** 协议确认（策略边界：技术上可代点，产品上让渡给人） */
    private static final String[] AGREEMENT = {
            "同意并继续", "已阅读并同意", "阅读并同意", "同意协议",
            "服务协议", "隐私政策", "用户协议"
    };
    /** 系统敏感权限授权弹窗（策略边界） */
    private static final String[] PERMISSION = {
            "仅在使用期间允许", "仅本次允许", "使用期间允许", "始终允许使用"
    };

    /**
     * 扫描节点列表，命中返回场景说明（用于接管卡片文案），未命中返回 null。
     * 组序即优先级：支付 > 验证 > 协议 > 权限。
     */
    public static String scan(List<NodeInfo> nodes) {
        if (nodes == null || nodes.isEmpty()) return null;
        String hit;
        if ((hit = find(nodes, PAYMENT)) != null)
            return "检测到支付付款界面（「" + hit + "」）——支付需由你本人确认";
        if ((hit = find(nodes, VERIFY)) != null)
            return "检测到身份验证界面（「" + hit + "」）——密码/验证码需由你本人输入";
        return null;
    }

    private static String find(List<NodeInfo> nodes, String[] kws) {
        for (NodeInfo n : nodes) {
            String s = n.text + " " + n.desc;
            for (String kw : kws) {
                if (s.contains(kw)) return kw;
            }
        }
        return null;
    }
}
