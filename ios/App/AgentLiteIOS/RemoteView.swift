// SPDX-License-Identifier: Apache-2.0
//
// 远程页：两个方向都在这里。
//   · 下发：iPhone → 其它设备（本端的强项）
//   · 收件：其它设备 → iPhone（受 iOS 后台限制，需前台触发或后台唤醒）
//
// ★ 界面上刻意写清两条**服务端现状**，而不是让用户自己撞：
//   1. 下发后本机查不到结果（终态 ack 后服务端删除该指令）；
//   2. iOS 作为被控端可能延迟响应（后台调度不可控）。

import SwiftUI
import AgentLiteCore

struct RemoteView: View {
    @EnvironmentObject private var model: AppModel

    @State private var task = ""
    @State private var targetDeviceID: String = ""

    var body: some View {
        Form {
            Section {
                if model.devices.isEmpty {
                    Text("没有可下发的目标设备。先在另一台设备用同一账号登录。")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    Picker("目标设备", selection: $targetDeviceID) {
                        ForEach(model.devices) { d in
                            Text("\(d.name ?? d.deviceID)（\(d.brand ?? "?")）").tag(d.deviceID)
                        }
                    }
                    TextField("要让对方做什么，例如：打开设置", text: $task, axis: .vertical)
                        .lineLimit(1...3)
                    Button {
                        Task {
                            await model.send(task: task, to: targetDeviceID)
                            task = ""
                        }
                    } label: {
                        Label("下发指令", systemImage: "paperplane")
                    }
                    .disabled(task.isEmpty || targetDeviceID.isEmpty || model.isBusy)
                }
            } header: {
                Text("下发（本机 → 其它设备）")
            } footer: {
                Text("指令 5 分钟内未被领取即作废；不支持广播，必须指定目标设备。"
                     + "对方回报结果后服务端会立刻删除该指令，因此**本机无法再查到执行结果** —— "
                     + "需要结果回执时请改用同步流（服务端尚未提供该通道）。")
            }

            Section {
                Button {
                    Task { await model.collect() }
                } label: {
                    Label("立即收取待办指令", systemImage: "tray.and.arrow.down")
                }
                .disabled(model.isBusy)

                if let report = model.lastDrain {
                    LabeledContent("领取", value: "\(report.fetched)")
                    LabeledContent("成功", value: "\(report.executed)")
                    LabeledContent("失败", value: "\(report.failed)")
                    if report.ackFailures > 0 {
                        Label("有 \(report.ackFailures) 条回报失败（下发方看不到结果，下次唤醒会重做）",
                              systemImage: "exclamationmark.triangle")
                            .font(.caption)
                            .foregroundStyle(.orange)
                    }
                    ForEach(Array(report.details.enumerated()), id: \.offset) { _, line in
                        Text(line).font(.caption).foregroundStyle(.secondary)
                    }
                }
            } header: {
                Text("收件（其它设备 → 本机）")
            } footer: {
                Text("iOS 不能常驻后台：App 在前台时请手动收取；退到后台后由系统调度刷新，"
                     + "**时机不可控**（可能几十分钟）。需要即时执行的任务请派给 Android 设备。")
            }
        }
        .navigationTitle("远程")
        .task {
            if targetDeviceID.isEmpty, let first = model.devices.first {
                targetDeviceID = first.deviceID
            }
        }
    }
}
