// SPDX-License-Identifier: Apache-2.0
//
// iOS 作为「指令目标设备」时的执行器。
//
// ★ 这是整个 iOS 端最需要诚实的一处，先讲清楚能力边界（手册 §1 有完整证据链）：
//   iOS **没有** Android 那种系统级 UI 自动化通道。三方 App 既拿不到
//   AccessibilityService 的节点树，也不能 dispatchGesture 去点别的 App。
//   因此 iOS **不能**像 Android 那样「收到指令 → 自己看屏幕 → 自己点」。
//
//   那 iOS 能做什么？三条真实可行的路，按可靠性排序：
//     ① 用户**预先授权过的本地动作**（App Intents / 快捷指令）——
//        用户事先把「打开某个 App」「发条笔记」做成快捷指令并授予权限，
//        收到指令时由本 App 调起它。可靠、可预期、无需越狱。
//     ② URL Scheme / Universal Link 跳转 —— 只能"打开"，不能"操作"。
//     ③ WebDriverAgent / XCUITest —— 需要 Mac + 开发者签名 + 开发者模式常开，
//        只适合研发场景，不是面向用户的产品形态（手册 §2 有对比表）。
//   凡是不属于上述三条的任务，本执行器一律**明确拒绝并回报 failed**，
//   而不是假装执行成功。跨设备指令的信任基础正是「结果不撒谎」。

import Foundation

/// 一条指令的执行结果。
///
/// 只用服务端认得的三种状态（running/done/failed）—— 见 `CommandStatus` 注释。
public struct ExecutionOutcome: Sendable, Equatable {
    public let status: CommandStatus
    public let detail: String

    public init(status: CommandStatus, detail: String) {
        self.status = status
        self.detail = detail
    }

    public static func done(_ d: String) -> ExecutionOutcome { .init(status: .done, detail: d) }
    public static func failed(_ d: String) -> ExecutionOutcome { .init(status: .failed, detail: d) }
    /// 「被拒绝」也走 failed：服务端状态机里没有 cancelled（command.go:166-171），
    /// 拒绝的理由写在 detail 里 —— 那才是用户真正要看的信息。
    public static func rejected(_ d: String) -> ExecutionOutcome { .init(status: .failed, detail: d) }
}

/// 本地动作的提供方。iOS 宿主实现它（接 App Intents / Shortcuts）；
/// 核心库只依赖这个协议 —— 于是 macOS 上也能用假实现跑完整链路测试。
public protocol LocalActionProvider: Sendable {
    /// 能处理哪些任务关键词（用于向用户展示「这台 iPhone 能做什么」）。
    var capabilities: [String] { get }

    /// 尝试执行一条本地任务。
    /// - Returns: nil 表示「不在能力范围内」——执行器据此回报 failed 并说明原因，
    ///   而不是静默成功。
    func perform(task: String) async throws -> ExecutionOutcome?
}

/// 什么都不做的空实现：装在 Mac/测试环境时用它，语义是「本机没有可执行动作」。
public struct NoLocalActionsProvider: LocalActionProvider {
    public init() {}
    public var capabilities: [String] { [] }
    public func perform(task: String) async throws -> ExecutionOutcome? { nil }
}

/// 指令执行器：把「收到一条跨设备指令」翻译成 iOS 上真实能做的事。
public actor CommandExecutor {
    private let provider: LocalActionProvider
    private let sensitiveGuard: SensitiveGuard

    public init(provider: LocalActionProvider = NoLocalActionsProvider(), sensitiveGuard: SensitiveGuard = SensitiveGuard()) {
        self.provider = provider
        self.sensitiveGuard = sensitiveGuard
    }

    /// 执行一条指令。**永远返回结果，不抛错**——抛错会让调用方漏掉 ack，
    /// 而下发方会一直等到指令 5 分钟过期，体验上等同于「石沉大海」。
    public func execute(_ task: String) async -> ExecutionOutcome {
        let trimmed = task.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            return .failed("任务文本为空")
        }

        // 1) 敏感操作红线：与 Android 侧同一套纪律。
        //    跨设备指令**不继承**下发方的任何授权，敏感动作一律拒绝。
        if let hit = sensitiveGuard.match(trimmed) {
            return .rejected("敏感操作（\(hit)）已按纪律拒绝：跨设备指令不继承授权，请在目标设备上人工确认")
        }

        // 2) 本地已授权的动作
        do {
            if let outcome = try await provider.perform(task: trimmed) {
                return outcome
            }
        } catch {
            return .failed("本地动作执行异常：\(error)")
        }

        // 3) 能力之外的请求：明确拒绝 + 给出可操作的建议。
        //    这里刻意不返回 done —— 「假装成功」比「失败」有害得多。
        return .failed(
            "iOS 端无法执行该任务：本机不具备系统级 UI 自动化能力（Apple 不向三方 App 开放）。"
            + "请在目标设备上预先配置快捷指令，或改由 Android 设备执行。"
            + "本机当前支持的动作：\(provider.capabilities.isEmpty ? "（未配置）" : provider.capabilities.joined(separator: "、"))"
        )
    }
}

/// 敏感操作识别。规则与 Android 侧 `SensitiveGuard` 保持同一取向：
/// 宁可多拦（用户重说一次），不可漏放（一次误操作不可逆）。
public struct SensitiveGuard: Sendable {
    /// 命中即拒绝的关键词（中英并集，覆盖支付/转账/验证码/删除/发送类）。
    public static let defaultKeywords: [String] = [
        "支付", "付款", "转账", "汇款", "红包", "打赏", "充值", "下单", "购买", "结算",
        "验证码", "短信码", "密码", "口令", "密钥",
        "删除", "卸载", "格式化", "恢复出厂", "抹掉",
        "发送", "群发", "发表", "评论", "转发",
        "pay", "transfer", "purchase", "checkout", "delete", "uninstall", "wipe", "send",
    ]

    private let keywords: [String]

    public init(keywords: [String] = SensitiveGuard.defaultKeywords) {
        self.keywords = keywords
    }

    /// 命中返回命中的关键词，否则 nil。
    public func match(_ text: String) -> String? {
        let lower = text.lowercased()
        for k in keywords where lower.contains(k.lowercased()) {
            return k
        }
        return nil
    }
}
