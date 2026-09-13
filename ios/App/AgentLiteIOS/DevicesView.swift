// SPDX-License-Identifier: Apache-2.0
//
// 设备页：同账号设备列表。
//
// 呈现重点（不是"列个表"）：让用户一眼看出**哪台设备能真正执行任务**。
// 这直接来自服务端上报的 brand/model —— iOS 端如实上报自己的平台（见 PlatformInfo），
// 所以这里能据此提示"iOS 设备只能执行预先授权的本地动作"，
// 避免用户把需要自动化的任务派给一台做不到的 iPhone。

import SwiftUI
import AgentLiteCore

struct DevicesView: View {
    @EnvironmentObject private var model: AppModel

    var body: some View {
        List {
            if model.devices.isEmpty {
                EmptyStateView(
                    "没有其它设备",
                    systemImage: "laptopcomputer.and.iphone",
                    description: "在另一台设备上用同一账号登录后，它会出现在这里。"
                )
            }
            ForEach(model.devices) { device in
                DeviceRow(device: device)
            }
        }
        .navigationTitle("设备")
        .toolbar {
            Button {
                Task { await model.refreshProfile() }
            } label: {
                Image(systemName: "arrow.clockwise")
            }
        }
        .refreshable { await model.refreshProfile() }
    }
}

private struct DeviceRow: View {
    let device: ServerDevice

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(device.name ?? device.deviceID)
                    .font(.headline)
                Spacer()
                PlatformBadge(device: device)
            }
            Text("\(device.brand ?? "?") / \(device.model ?? "?") · \(device.deviceID)")
                .font(.caption)
                .foregroundStyle(.secondary)
            if let lan = device.lanIPs, !lan.isEmpty {
                // 局域网可达 = 可以走 P2P（数据不过服务器）
                Label("局域网可达 \(lan.joined(separator: ", ")):\(device.lanPort ?? 0)",
                      systemImage: "wifi")
                    .font(.caption2)
                    .foregroundStyle(.green)
            }
            if let note = capabilityNote {
                Text(note)
                    .font(.caption2)
                    .foregroundStyle(.orange)
            }
        }
        .padding(.vertical, 4)
    }

    /// 依据服务端上报的平台给出能力提示。
    /// 判据用 brand（服务端字段），不要猜 —— iOS 端自己上报 "Apple"。
    private var capabilityNote: String? {
        let brand = (device.brand ?? "").lowercased()
        if brand.contains("apple") && !brand.contains("macos") {
            return "iOS 设备：只能执行预先授权的本地动作（打开 App / 快捷指令），不能自动操作其它 App"
        }
        return nil
    }
}

private struct PlatformBadge: View {
    let device: ServerDevice

    var body: some View {
        Text(label)
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 3)
            .background(color.opacity(0.18), in: Capsule())
            .foregroundStyle(color)
    }

    private var label: String {
        let brand = (device.brand ?? "").lowercased()
        if brand.contains("macos") { return "macOS" }
        if brand.contains("apple") { return "iOS" }
        if brand.contains("honor") || brand.contains("hihonor") { return "HarmonyOS" }
        if brand.isEmpty { return "未知" }
        return "Android"
    }

    private var color: Color {
        switch label {
        case "iOS": return .blue
        case "Android": return .green
        case "macOS": return .purple
        default: return .gray
        }
    }
}
