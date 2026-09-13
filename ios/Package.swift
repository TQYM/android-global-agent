// swift-tools-version:5.9
// SPDX-License-Identifier: Apache-2.0
//
// AgentLite iOS —— 账号 + 远程控制端（详见 docs/IOS_IMPLEMENTATION_MANUAL.zh-CN.md）
//
// 平台策略（why）：
//   - 核心库 AgentLiteCore **不导入 UIKit / SwiftUI / AppKit**，只用 Foundation + CryptoKit。
//     于是同一份源码既能进 iOS App，也能在 macOS 上 `swift test` 直接跑——
//     这是本项目「无法真机验证时不靠猜」的落地方式（对比：Android 侧的
//     tests/verify_lan_p2p.sh 用桌面 JVM 做对端模拟，同一思路）。
//   - iOS 专属能力（App Intents / 后台刷新 / Keychain 访问组）isolated 在
//     AgentLiteIOS 目标，核心库不依赖它们。
//   - PBKDF2 需要 CommonCrypto → 用 CAgentLiteShim 这个 C 目标把系统头文件
//     暴露给 Swift。**为什么不写自研 PBKDF2**：自研密码学会把「密钥两端不一致」
//     变成一个静默故障（P2P 一直不通，却没有报错），可验证性反而更差。

import PackageDescription

let package = Package(
    name: "AgentLite",
    platforms: [
        .iOS(.v16),
        .macOS(.v13),
    ],
    products: [
        .library(name: "AgentLiteCore", targets: ["AgentLiteCore"]),
        .executable(name: "agentlite-ios", targets: ["AgentLiteCLI"]),
    ],
    targets: [
        .target(
            name: "CAgentLiteShim",
            path: "Sources/CAgentLiteShim"
        ),
        .target(
            name: "AgentLiteCore",
            dependencies: ["CAgentLiteShim"],
            path: "Sources/AgentLiteCore"
        ),
        .executableTarget(
            name: "AgentLiteCLI",
            dependencies: ["AgentLiteCore"],
            path: "Sources/AgentLiteCLI"
        ),
        .testTarget(
            name: "AgentLiteCoreTests",
            dependencies: ["AgentLiteCore"],
            path: "Tests/AgentLiteCoreTests"
        ),
    ]
)
