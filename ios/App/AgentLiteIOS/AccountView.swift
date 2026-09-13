// SPDX-License-Identifier: Apache-2.0
//
// 账号页：登录 / 注册 / 查看额度 / 退出。
//
// 账号名与密钥的**预校验**刻意放在核心库（AccountService.validate），
// 这里只做输入与错误呈现 —— 规则只有一份，避免两端校验漂移。

import SwiftUI
import AgentLiteCore

struct AccountView: View {
    @EnvironmentObject private var model: AppModel

    @State private var name = ""
    @State private var key = ""
    @State private var registerNew = false

    var body: some View {
        Form {
            Section {
                TextField("账号名（3-32 位小写字母/数字/_/-）", text: $name)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                SecureField("账号密钥（至少 8 位）", text: $key)
            } header: {
                Text("账号")
            } footer: {
                // 说清"密钥丢了会怎样"，而不是只写"请妥善保管"
                Text("账号密钥同时用于派生局域网同步密钥，服务器不保存它。密钥丢失后无法找回，只能重新注册。")
            }

            Section {
                Toggle("注册新账号", isOn: $registerNew)
                Button {
                    Task { await model.login(name: name, key: key, registerNew: registerNew) }
                } label: {
                    if model.isBusy {
                        ProgressView()
                    } else {
                        Text(registerNew ? "注册并绑定本机" : "登录")
                    }
                }
                .disabled(name.isEmpty || key.isEmpty || model.isBusy)
            }

            if case .ready = model.phase {
                Section("已登录") {
                    LabeledContent("账号", value: model.accountName)
                    LabeledContent("额度", value: "\(model.quota.used) / \(model.quota.total)")
                    Button("退出登录", role: .destructive) {
                        Task { await model.logout() }
                    }
                }
            }
        }
        .navigationTitle("账号")
        .overlay(alignment: .bottom) {
            if !model.lastMessage.isEmpty {
                Text(model.lastMessage)
                    .font(.footnote)
                    .padding(10)
                    .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 10))
                    .padding()
            }
        }
    }
}
