// SPDX-License-Identifier: Apache-2.0
//
// 指令收件箱：把「拉取 → 执行 → 回报」串成一次可重入的动作。
//
// 这个类存在的核心理由是 iOS 的后台模型（手册 §5.3）：
//   系统只在它愿意的时候给几十秒（BGAppRefreshTask），**不给**常驻后台进程。
//   所以 iOS 端不能像 Android 那样开 20 秒轮询的常驻线程
//   （RemoteCommand.java:42 POLL_INTERVAL_MS=20_000），
//   只能把「一次完整收取」做成一个可被系统随时唤醒调用的短任务：
//   拉一次 → 逐条执行 → 逐条 ack → 立刻结束。
//   超时预算用尽就停下并保留未处理项，等下次被唤醒——这正是「拉取式自愈」。

import Foundation

public actor CommandInbox {
    private let commands: CommandService
    private let executor: CommandExecutor

    /// 单次收取的时间预算。iOS 后台刷新给的时间有限（系统给的窗口量级是几十秒），
    /// 留足余量：宁可少执行一条，也不要被系统强杀 —— 强杀会让 ack 丢失，
    /// 下发方那边看到的仍是 pending，用户以为没生效而重复下发。
    public static let defaultBudget: TimeInterval = 25

    public init(commands: CommandService, executor: CommandExecutor) {
        self.commands = commands
        self.executor = executor
    }

    public struct DrainReport: Sendable, Equatable {
        public var fetched: Int = 0
        public var executed: Int = 0
        public var failed: Int = 0
        public var skippedForBudget: Int = 0
        /// 回报失败条数。单列一项是因为它意味着「本地做完了但下发方不知道」，
        /// 需要与「任务本身失败」区分开看。
        public var ackFailures: Int = 0
        public var details: [String] = []
        /// 已回报终态的指令（含服务端确认后的 status/detail）。
        ///
        /// ★ 为什么要把它带出来、而不是让调用方再去查一次：
        /// 服务端在收到终态 ack 后会把指令**从内存表里删除**（command.go:195），
        /// 之后 `GET /device/command?id=` 一律返回 404。
        /// 所以「a​ck 的返回值」是这条指令结果的**唯一**可获得时刻，
        /// 丢掉它就只能靠对方设备自己留存结果了。
        public var acked: [SyncedCommand] = []

        public var summary: String {
            "领取 \(fetched) 条，成功 \(executed) 条（其中回报失败 \(ackFailures) 条），失败 \(failed) 条"
            + (skippedForBudget > 0 ? "，因时间预算用尽剩余 \(skippedForBudget) 条待下次" : "")
        }
    }

    /// 收一次件。
    ///
    /// ★ 关键顺序：**先执行，再回报终态；绝不在执行前先 ack `running`**。
    /// 原因（读服务端源码得出，command.go:149 + 186）：`PendingFor` 只返回
    /// `status == pending` 的指令，而 ack 成 `running` 后它就不再出现在待办列表里。
    /// 于是「先标 running 再执行」的组合一旦在执行途中断电/被系统杀进程，
    /// 这条指令就**永远不会被再次领取**，下发方也只能一直看到 running ——
    /// 一个不可恢复的中间态。
    /// 先执行再 ack 则相反：中途失败顶多「这次没回报」，下次唤醒仍能领到并重做
    ///（代价是任务可能被执行两次；跨设备指令以「最终有结果」优先，
    ///  且本地动作本身应当幂等）。
    @discardableResult
    public func drainOnce(budget: TimeInterval = CommandInbox.defaultBudget) async throws -> DrainReport {
        var report = DrainReport()
        let pending = try await commands.pending()
        report.fetched = pending.count
        let deadline = Date().addingTimeInterval(budget)

        for cmd in pending {
            if Date() >= deadline {
                report.skippedForBudget = pending.count - report.executed - report.failed
                break
            }

            let task = cmd.task.hasPrefix(RemoteTaskPrefix.marker)
                ? String(cmd.task.dropFirst(RemoteTaskPrefix.marker.count))
                : cmd.task

            // 执行（executor 保证不抛错：它总是返回一个结果）
            let outcome = await executor.execute(task)

            // 回报终态。这是「必须成功」的一步：失败就意味着下发方看不到结果，
            // 所以单独计数并写进 details，而不是悄悄吞掉。
            do {
                let acked = try await commands.ack(commandID: cmd.commandID, status: outcome.status, detail: outcome.detail)
                report.acked.append(acked)
                if outcome.status == .done { report.executed += 1 } else { report.failed += 1 }
                report.details.append("[\(cmd.commandID.prefix(8))] \(outcome.status.rawValue): \(outcome.detail)")
            } catch {
                report.ackFailures += 1
                report.failed += 1
                report.details.append("[\(cmd.commandID.prefix(8))] 回报失败（下次唤醒会重做该指令）: \(error)")
            }
        }
        return report
    }
}

/// 跨设备指令的任务前缀。与 Android 侧 `RemoteCommand.REMOTE_PREFIX` 同值 ——
/// 两端对同一条指令的文本形态必须一致，否则「这句话是谁说的」在跨端时会漂移。
public enum RemoteTaskPrefix {
    public static let marker = "[跨设备指令] "
}
