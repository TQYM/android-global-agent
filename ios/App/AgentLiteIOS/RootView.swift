// SPDX-License-Identifier: Apache-2.0
//
// 根视图：按登录态分派。
//
// UI 纪律（与项目既有约定一致）：界面只负责**呈现状态与发起动作**，
// 不做协议判断，也不"猜"服务端行为 —— 那些都在 AgentLiteCore（有测试）。

import SwiftUI
import AgentLiteCore

struct RootView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        NavigationStack {
            switch model.phase {
            case .loading:
                ProgressView("正在恢复登录态…")

            case .notConfigured(let reason):
                // 未配置与未登录必须分开呈现：否则用户会去查账号，而问题在配置。
                EmptyStateView(
                    "未配置服务端",
                    systemImage: "gearshape.badge.xmark",
                    description: reason + "\n\n把服务端证书放进 App 资源（Resources/server.crt），并在 Info.plist 设置 AgentLiteBaseURL。"
                )

            case .loggedOut:
                AccountView()

            case .ready:
                TabView {
                    NavigationStack { RootView.placeholder("账号", systemImage: "person.crop.circle") }
                        .tabItem { Label("账号", systemImage: "person.crop.circle") }
                    NavigationStack { DevicesView() }
                        .tabItem { Label("设备", systemImage: "laptopcomputer.and.iphone") }
                    NavigationStack { RemoteView() }
                        .tabItem { Label("远程", systemImage: "arrow.left.arrow.right") }
                }
            }
        }
        .overlay(alignment: .bottom) { messageBar }
    }

    /// 统一的提示条。把 lastMessage 放在一处显示，避免每个页面各自弹窗。
    @ViewBuilder
    private var messageBar: some View {
        if !model.lastMessage.isEmpty {
            Text(model.lastMessage)
                .font(.footnote)
                .padding(10)
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 10))
                .padding(.horizontal)
                .padding(.bottom, 60)
                .transition(.opacity)
        }
    }

    static func placeholder(_ title: String, systemImage: String) -> some View {
        EmptyStateView(title, systemImage: systemImage, description: "见「设备」与「远程」页")
            .navigationTitle(title)
    }
}
