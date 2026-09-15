# Android Agent 安全审查报告（2026-09-15）

- **审查范围**：全仓四条线——① 2026-09-05 旧报告 P0/P1 修复复核（agentd-go / agentd-apk）；② `agentlite-server/`（与 `agent-unified/server/` 逐字节相同）首次安全审查；③ Android 客户端 `agent-lite/`（新模块）与 `agent-client/`；④ 仓库卫生（密钥泄露、git 跟踪物、脚本、C++/iOS 残留面）。
- **方法**：三路并行子代理深度审查 + 主审逐项抽查交叉验证（agentd-go auth.go 全文、iOS LANPeer/TlsPin 路径、全仓密钥模式扫描）。所有结论带 `文件:行号` 证据。
- **威胁模型**：局域网内恶意主机/同机恶意 App（loopback 非应用级边界）；模型输入含不可信屏幕文本（提示注入）；服务端暴露公网（`0.0.0.0:18443`）面对扫描与滥用。

---

## 1. 执行摘要

本次共记录 **5 项 P0、12 项 P1**（跨三条线），另 P2/P3 数十项。

**上轮（09-05）的「局域网遥控/窥屏」P0 链条已实质闭合**：agentd-go 只绑 127.0.0.1、全部 `/api/*` 过「随机令牌（常量时间比较）+ Host/Origin/方法/Content-Type」四重校验且有回归测试；8081 桥同步绑回环并加令牌；BaseURL 强制 https。工程亮点：零第三方依赖、令牌只存 sha256、PBKDF2 + 抗枚举时序、客户端 TLS pin + 远程配置白名单（服务端失陷也改不了客户端 `base_url`/`api_key`）。

**新的风险重心转移为三处**：

1. **agentlite-server 计费/额度治理（P0×3，上线即被利用）**：计费依赖客户端可显式关闭的 `include_usage`（P0-S1）；注册零验证 + 每新账号白送 50 万 tokens，可规模化盗刷上游付费 Key（P0-S2）；余额「先检查后扣减」竞态使超支无上限（P0-S3）。三者叠加 = 不可控资金损失。
2. **agent-lite 新引入的本地提权与隐私泄露（P0×2）**：导出的 `MainActivity` 接受外部 `task`/`auto_run` extra 并自动执行——任意 App 一条 intent 即可驱动无障碍代理（P0-A1）；无障碍节点采集无 `isPassword` 过滤，密码/验证码明文进入模型提示词（P0-A2）。
3. **「模型输出 → 高权限动作」授权边界仍不完整（P1）**：`open_url` 无 scheme 白名单（`intent://` 可启动任意组件，agentd-go 以 root 执行、agent-lite 无 ActionValidator）；敏感操作无代码级确认 gate；`ask` 超时被当作"可继续"回流模型。

---

## 2. 上轮（09-05）P0/P1 修复复核（附录 A）

| 编号 | 标题 | 状态 |
|---|---|---|
| P0-1 | agentd-go 全接口监听 + 零认证 | **已修复**（127.0.0.1 + 令牌 + 四重校验 + 回归测试） |
| P0-2 | agentd-apk 8081 零鉴权读屏/注入 | **部分**（已绑回环 + 令牌；`walk()` 仍无 `isPassword` 过滤与上限，`/settext` 回退首个可编辑节点） |
| P1-1 | 未授权改写 BaseURL 窃 Key | **已修复**（强制 https、拒 user:pass、GET 只回哨兵） |
| P1-2 | /screen /status 未授权泄露 | **已修复**（均过 guard） |
| P1-3 | CSRF / DNS 重绑定 | **已修复**（Host 回环 + Origin 同源 + 方法 + JSON Content-Type） |
| P1-4 | LLM 输出→root 动作无白名单 | **未修复**（`open_url` 仍收 `intent://`；pkg/keycode 无白名单；建议移植 agent-client 的 `ActionValidator` 到 `agentd-go/internal/agent/agent.go` 的 `exec()` 入口） |
| P1-5 | 提示注入→root 操作无代码级确认 | **未修复**（`ToPrompt` 原样拼接、exec 前无确认 gate、WebUI 无 confirm） |
| P1-6 | IME 提交广播等效导出 | **部分**（迁至 floris-merge，但权限 `protectionLevel="normal"` 形同虚设；注入文本写外部存储 `bridge.log`） |
| P1-7 | API Key 明文 + allowBackup | **部分**（输入框已 textPassword；`Prefs.java` 仍明文存储，manifest 未关 allowBackup；agent-lite 的 `TokenStore` 可直接复用） |
| P1-8 | C++ adb 通道命令注入 | **不适用**（C++ 核心已整体移除） |

遗留 P2：agentd-go 数据目录仍 0755、截图产物按 umask；`agentd-apk` 全局 `usesCleartextTraffic="true"`（实际只需回环）。

---

## 3. 分报告索引

- **附录 B**：`agentlite-server` 安全审查（`SECURITY_REVIEW_2026-09-15.serverlite.md`）——P0×3 / P1×6 / P2×9 / P3×10 + 22 端点×认证总表 + 12 项「已检查无发现」维度。要点：
  - **P0-S1** `gateway.go:239-263` 客户端带 `"stream_options":{"include_usage":false}` 即跳过计费注入，流式调用近乎免费；
  - **P0-S2** `api.go:220-224` 注册零验证（仅每 IP 限次，IPv6/代理池可绕）+ `quota.go:23` 每号送 500 units，可盗刷上游付费 Key；
  - **P0-S3** `gateway.go:64` 检查与扣减非原子且无并发上限，超支 = N × 单次成本，N 无上限；
  - P1：`stream_options:null` 触发 nil-map panic（`gateway.go:244-250`）；登录在全局写锁内跑 21 万次 PBKDF2（`account.go:240-259`）；`loggingWriter` 缺 `Unwrap/Flush` 致流式写期限豁免与 SSE flush 双失效；令牌永不过期无吊销；status 无大小上限 × devices.json 全量重写 = 写放大 DoS；下单无限流二次方写盘。
- **附录 C**：Android 客户端审查（`SECURITY_REVIEW_2026-09-15.android.md`）——P0×2 / P1×6 / P2×11 / P3×5 + exported 组件总表。要点：
  - **P0-A1** `agent-lite/MainActivity.java:701-721` 处理外部 `task`/`auto_run`，任意 App 一条 intent 自动执行任务；
  - **P0-A2** `AgentA11yService.java:271-297` `walk()` 无 `isPassword` 过滤，密码明文进提示词；
  - P1：`api_key`/`lan_key` 明文 SharedPreferences；`A11yGuard` 清空式写无障碍设置（关闭用户其它读屏服务，属防护回退）；IME 权限 `normal` 级；`open_url` 无 scheme 白名单；`AssistActivity` 导出且自动录音；
  - P2 回退三处（相对 agent-client）：锁屏通知明文任务内容、剪贴板不清理、无障碍设置覆盖式写入；
  - 正向：无 WebView、TLS pin、配置白名单、`TokenStore` Keystore AES-GCM、构建脚本禁止 API_KEY 内联进 APK（agent-lite）。

## 4. 修复优先级（最小改动路径）

1. **发布前必做（资金损失）**：P0-S1 强制 `include_usage=true` + `max_tokens` 服务端上限；P0-S2 赠送额度需兑换码激活/降额 + `/v1/*` 并发闸门；P0-S3 改预扣+结算。
2. **立即（本地提权/隐私）**：P0-A1 `handleIntent` 校验调用方（签名权限或 `getLaunchedFromPackage`）；P0-A2 `walk()` 掩码 `isPassword` 节点 + 提示词出口兜底过滤（agentd-apk `walk()` 同修）。
3. **本周（授权边界）**：`ActionValidator` 移植到 agentd-go `exec()` 与 agent-lite `open_url`（拒 `intent:`/`file:`，pkg/keycode 白名单）；敏感操作代码级确认 gate；`ask` 超时强制视为拒绝；IME 权限升 `signature`。
4. **存储与卫生**：`api_key`/`lan_key` 迁 Keystore；agent-client 关 allowBackup；agent-lite 无障碍设置改追加式并去掉清空；锁屏通知 `VISIBILITY_SECRET`；剪贴板清理移植；serverlite P1-1 nil map 一行修复、P1-2 PBKDF2 移出锁、P3-1 兜底路由入 `wrap`。

## 5. 仓库卫生（主审自查）

全仓 `PRIVATE KEY`/`sk-*`/`AKIA`/硬编码口令扫描 **0 命中**；`agentlite-server/tls/` 仅公钥证书入库，工作区 `server.key` 均未跟踪且被 ignore；C++ 核心已移除（危险函数扫描无对象）；脚本无 `eval`/`curl|sh`；凭据一律环境变量注入；iOS 主链路 HTTPS + CertificatePinner，LANPeer 明文 HTTP 但载荷有 AEAD 应用层加密 + AAD + 新鲜度校验（设计可接受，P3）。iOS 默认服务器硬编码公网 IP `8.133.225.105:18443`（与 smoke-test.sh 一致，注意测试凭据暴露面，见附录 B P3-10）。
