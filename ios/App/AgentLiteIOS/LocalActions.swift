// SPDX-License-Identifier: Apache-2.0
//
// 本地动作提供方 —— iOS 上"这台设备究竟能替用户做什么"的**唯一**实现处。
//
// ★ 这是整个 iOS 端最需要克制的文件，先把边界钉死：
//   iOS **不能**替用户操作别的 App（无系统级无障碍、无跨 App 手势注入）。
//   本文件能做的只有两件真实可行的事：
//     ① 打开一个 App（URL Scheme / 系统设置页）—— 只能"打开"，不能"操作"；
//     ② 调起用户**预先创建**的快捷指令（x-callback-url 进 Shortcuts）——
//        动作内容由用户自己定义并授权，本 App 只是"按一下按钮"。
//   任何超出这两类的任务，一律返回 nil（由 CommandExecutor 回报 failed + 原因）。
//
//   为什么不做"猜一个 URL Scheme 就去打开"的聪明事：猜错的后果是弹出
//   乱七八糟的 App 或静默失败，用户看到的是"这 App 乱跳"。宁可少做、说清楚。

import Foundation
import UIKit
import AgentLiteCore

struct IOSLocalActionProvider: LocalActionProvider {

    /// 向用户展示的能力清单（也会出现在"做不到"的失败理由里，便于用户改配置）。
    var capabilities: [String] {
        ["打开设置", "打开相机", "打开相册", "打开浏览器", "运行快捷指令（需预先创建）"]
    }

    func perform(task: String) async throws -> ExecutionOutcome? {
        let text = task.trimmingCharacters(in: .whitespacesAndNewlines)

        // ① 系统设置类
        if let url = Self.settingsURL(for: text) {
            return try await open(url: url, describing: "已打开设置页")
        }

        // ② 固定 App 的 URL Scheme
        if let (url, name) = Self.appURL(for: text) {
            return try await open(url: url, describing: "已打开 \(name)")
        }

        // ③ 快捷指令：`运行快捷指令 <名字>`
        if let name = Self.shortcutName(from: text) {
            // Shortcuts 的 x-callback-url：/run-shortcut?name=<名字>
            var comps = URLComponents(string: "shortcuts://x-callback-url/run-shortcut")
            comps?.queryItems = [URLQueryItem(name: "name", value: name)]
            guard let url = comps?.url else {
                return .failed("快捷指令名字无法编码：\(name)")
            }
            return try await open(url: url, describing: "已调起快捷指令「\(name)」（动作由你预先定义并授权）")
        }

        // ④ 能力之外：返回 nil，交给 CommandExecutor 统一回报 failed
        return nil
    }

    // MARK: - 内部

    /// 必须在主线程碰 UIApplication。核心库是 actor 化的，这里显式跳主线程。
    @MainActor
    private func openURL(_ url: URL) async -> Bool {
        await withCheckedContinuation { cont in
            UIApplication.shared.open(url, options: [:]) { ok in cont.resume(returning: ok) }
        }
    }

    private func open(url: URL, describing what: String) async throws -> ExecutionOutcome {
        let ok = await openURL(url)
        if ok { return .done(what) }
        // 打不开 ≠ 成功。URL Scheme 未安装时系统会返回 false，
        // 这时报"已打开"就是撒谎 —— 跨设备指令的信任基础就是不撒谎。
        return .failed("\(what.replacingOccurrences(of: "已", with: "未能"))：系统拒绝了该 URL（\(url.scheme ?? "?")://，对应 App 可能未安装）")
    }

    /// 系统设置页。只覆盖确实由本项目产出的场景：让用户去开权限。
    private static func settingsURL(for text: String) -> URL? {
        if text.contains("设置") {
            return URL(string: UIApplication.openSettingsURLString)
        }
        return nil
    }

    /// 固定 App 的 URL Scheme。**只收录确定的 scheme**，不猜。
    private static func appURL(for text: String) -> (URL, String)? {
        // 顺序有意义：先匹配更具体的关键词，避免"打开相机"被"相机"之外的规则抢走
        let table: [(keyword: String, scheme: String, name: String)] = [
            ("相机", "camera://", "相机"),
            ("相册", "photos-redirect://", "相册"),
            ("浏览器", "https://", "浏览器"),
            ("Safari", "https://", "Safari"),
        ]
        for row in table where text.contains(row.keyword) {
            if let url = URL(string: row.scheme) { return (url, row.name) }
        }
        return nil
    }

    /// 从「运行快捷指令 名字」里取名字。前缀必须显式，不做模糊匹配。
    private static func shortcutName(from text: String) -> String? {
        for prefix in ["运行快捷指令", "执行快捷指令", "快捷指令"] {
            if text.hasPrefix(prefix) {
                let name = text.dropFirst(prefix.count)
                    .trimmingCharacters(in: CharacterSet(charactersIn: " ：:，,"))
                if !name.isEmpty { return name }
            }
        }
        return nil
    }
}
