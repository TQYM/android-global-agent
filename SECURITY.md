# 安全政策（Security Policy）

## 支持范围

本项目仍处于开发与研究阶段，只对默认分支的最新代码提供安全响应。已发布的预编译 APK 不承诺单独的安全回补。

## 报告安全问题

请勿在公开 Issue 中披露未修复的安全漏洞。优先使用 GitHub 仓库的 **Security Advisories**（「Report a vulnerability」）私下提交；如不可用，请开 Issue 仅说明「存在待私下沟通的安全问题」并留下可联系方式，维护者会主动联系。

报告请尽量包含：影响组件（`agent-lite` / `agent-client` / `agentd-go` / `agentd-apk`）、复现步骤、影响评估与可能的修复建议。

## 项目自身安全边界

使用本项目时请遵守（摘自 README 与安全审查）：

- 仅用于自有设备或已获得明确授权的设备；
- 不捕获或绕过 `FLAG_SECURE`、DRM、硬件密钥存储、应用沙箱及系统安全机制；
- Agent 不得在支付、转账、验证码、隐私协议等高风险步骤中自行确认；
- API Key、签名文件、测试 APK、日志、截图和设备标识不得提交到公共仓库；
- Root 组件（`agent-client` 沙盒、`agentd-go`/`agentd-apk`）只应在隔离测试设备上使用，坚持最小权限和本机访问原则。

历史安全审查：[`SECURITY_REVIEW_2026-09-05.zh-CN.md`](SECURITY_REVIEW_2026-09-05.zh-CN.md)。
