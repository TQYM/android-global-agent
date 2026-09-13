# AgentLite for iOS

> **本目录是 iOS 端的实现。完整设计说明、能力边界与服务端契约在
> [`docs/IOS_IMPLEMENTATION_MANUAL.zh-CN.md`](../docs/IOS_IMPLEMENTATION_MANUAL.zh-CN.md) —— 动手前请先读它。**

## 一句话定位

**iOS 端做「账号 + 信息同步 + 远程控制」；不做「设备端自动操作别人 App」。**

原因不是我们没实现，而是 iOS 不向第三方 App 开放系统级无障碍与跨 App 手势注入
（与 Android 的 `AccessibilityService` / `dispatchGesture` 无对应物）。
详细证据链见手册 §1。能力之外的任务，本端会**明确回报 `failed` 并说明原因**，绝不假装成功 ——
跨设备指令的信任基础就是「结果不撒谎」。

## 目录结构

```
ios/
├── Package.swift            # iOS 16+ / macOS 13+；核心库不依赖 UIKit
├── build-mac.sh             # 构建 + 验证（--e2e 端到端 / --simulator 模拟器）
├── Sources/
│   ├── CAgentLiteShim/      # C shim：把 CommonCrypto 的 PBKDF2 暴露给 Swift
│   ├── AgentLiteCore/       # 核心库（平台无关，Mac 上可全量验证）
│   └── AgentLiteCLI/        # 命令行外壳（真机以外验证协议）
├── Tests/AgentLiteCoreTests/    # 42 项：密码学交叉验证 / 协议 / 端到端
└── App/                     # iOS App（Xcode 工程）
    ├── generate-project.py  # 生成 .pbxproj（--check 供门禁；不要手改工程文件）
    ├── scripts/prepare-resources.sh   # 生成/导入联调证书 + 打印 SPKI 指纹
    ├── AgentLiteIOS/        # SwiftUI 四屏 + 后台刷新 + 本地动作
    ├── AgentLiteIOSTests/   # 模拟器专属测试（Keychain / ATS / 沙盒 / 端到端）
    └── Resources/           # server.crt（脚本生成，不入库）
```

**为什么核心库刻意不依赖 UIKit**：这样同一份源码既进 iOS App，也能在 macOS 上
`swift test` 直接跑（含真连服务端的端到端用例）。iOS 端联调成本最高（装机/签名），
所以把可自动化的部分全部前移到桌面完成 —— 与 Android 侧
`tests/verify_lan_p2p.sh` 用桌面 JVM 当对端模拟是同一思路。

## 快速开始

```bash
# 离线验证（无网络依赖）：构建 + 32 项测试
bash build-mac.sh

# 全量验证（需先按手册 §8.1 起本地服务端）
bash build-mac.sh --e2e          # 追加 10 项真连服务端的端到端用例

# 追加 iOS App：工程一致性校验 + 模拟器构建与测试
bash build-mac.sh --simulator
```

### iOS App（`ios/App/`）

```bash
bash ios/App/scripts/prepare-resources.sh   # 生成联调证书（证书 pin 的构建输入）
python3 ios/App/generate-project.py         # 生成 Xcode 工程（勿手改）
bash ios/build-mac.sh --simulator           # 在模拟器上构建并跑测试
```

> ⚠️ **模拟器前置条件**：Xcode 的 SDK 版本必须与已安装的模拟器运行时版本匹配。
> 不匹配时 `xcodebuild` 的 destination 不可用（报 `iOS 26.5 is not installed`），
> **而 `simctl` 仍能正常 boot 设备** —— 两个工具口径不同，别被 simctl 骗了。
> 本机实况与修复步骤见实现手册 §8.3。

> ⚠️ **真机**：需要登录 Apple ID（免费账号 7 天有效）或开发者账号。本机当前
> `security find-identity -p codesigning` 为 **0 个身份**，`devicectl` 无设备。

CLI 手工联调：

```bash
cd ios && swift build
export AGENTLITE_BASE="https://8.133.225.105:18443"
export AGENTLITE_PIN="$(swift run -q agentlite-ios pin --cert ../agentlite-server/tls/server.crt)"

swift run agentlite-ios register --name myacct --key 至少八位密钥
swift run agentlite-ios whoami
swift run agentlite-ios devices
swift run agentlite-ios send --to d_xxx --task "打开设置"
swift run agentlite-ios collect          # 本机作为目标设备收件
```

## 测试基线（2026-09-13 实测）

| 套件 | 项数 | 覆盖 |
|---|---|---|
| `CryptoTests` | 13 | PBKDF2 / AES-256-GCM 与 **Java 逐字节交叉验证**、篡改与错误 AAD 必须失败 |
| `ProtocolTests` | 19 | 线格式字段名、敏感红线、抗重放、设备指纹稳定性、服务端状态机 |
| `EndToEndTests` | 10 | **真连服务端**：注册/登录/绑定/下发/领取/回报/同步/局域网互解 |

另有 `ios/App/AgentLiteIOSTests/SimulatorTests.swift`（**模拟器专属**）：
Keychain 属性、凭据跨重启持久化、iOS 沙盒路径、ATS + 证书 pin 的拒绝路径、端到端。

合计 **42 项全绿**；macOS、`arm64-apple-ios16.0`（真机）与
`arm64-apple-ios16.0-simulator`（模拟器）三个目标均编译/类型检查通过。

## 平台事实（写代码前务必知道）

1. **后台不能常驻**：指令领取延迟不可控（Android 是 20 秒轮询，iOS 只能靠前台轮询 + 后台唤醒）。
2. **局域网 P2P 只能是发起方**：iOS 无法常驻监听 socket，由 Android 侧当监听方。
3. **自签证书必须 pin**：iOS 默认拒绝自签证书（实测 `-1202`），空 pin 集合 = 拒绝一切连接。
4. **指令服务端只认 `running`/`done`/`failed`**：没有 `cancelled`；终态 ack 后服务端即删除该指令。
5. **执行顺序：先执行、后 ack 终态** —— 先报 `running` 会让指令永久卡死（服务端不再返回它）。

以上每条都有对应的测试或断言守着，详见手册 §6、§7、§10。
