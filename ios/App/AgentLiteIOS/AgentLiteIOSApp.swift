// SPDX-License-Identifier: Apache-2.0
//
// AgentLite iOS App 入口。
//
// 这一层的职责**只有三件**（其余全在 AgentLiteCore 里，那部分在 macOS 上可全量验证）：
//   1. 把 SwiftUI 界面接到核心库；
//   2. 提供 iOS 专属能力：本地动作（URL Scheme / 快捷指令）与后台刷新；
//   3. 平台相关的配置（证书 pin 的来源、后台任务注册）。
//
// 纪律：本文件里**不写业务逻辑**。任何"协议怎么调、状态怎么变"的判断都应下沉到
// AgentLiteCore —— 那样才能在没有模拟器/真机的情况下被测到。

import SwiftUI
import AgentLiteCore

@main
struct AgentLiteIOSApp: App {
    @StateObject private var model: AppModel

    init() {
        let m = AppModel()
        _model = StateObject(wrappedValue: m)
        // 后台任务必须在启动完成前注册；失败在模拟器上是预期行为，不影响前台使用。
        BackgroundRefresh.register(model: m)
        BackgroundRefresh.scheduleNext()
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .task {
                    // 启动即恢复登录态；失败不弹窗打断，交由各页面按状态提示
                    await model.bootstrap()
                }
        }
    }
}
