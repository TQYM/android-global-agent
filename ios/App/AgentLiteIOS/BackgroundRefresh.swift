// SPDX-License-Identifier: Apache-2.0
//
// 后台刷新 —— iOS 端"被控端"可靠性的关键，也是与 Android 最大的工程差异。
//
// 事实（手册 §6）：iOS **没有** Android 那种常驻前台服务。
// BGAppRefreshTask 由系统按使用习惯调度，时机不可控（可能几十分钟不触发），
// 而跨设备指令 TTL 只有 5 分钟。因此：
//   - 前台：App 活跃时手动/定时收取，这是主路径；
//   - 后台：注册 BGAppRefreshTask，被唤醒时做一次收取；
//   - 兜底：App 回到前台时立刻收一次（用户切回来就能看到结果）。
//
// ★ 不做的事（想清楚再决定要不要加）：
//   注册"每 15 分钟一次"的期望是错的 —— `earliestBeginDate` 只是**最早可能**时间，
//   不是承诺。写死短间隔只会让系统更快判定"这个 App 在滥用后台"从而减少调度。
//   正确做法是每次任务结束时重新申请下一次，并接受"可能很久不来"。

import Foundation
import BackgroundTasks
import AgentLiteCore

@MainActor
enum BackgroundRefresh {

    static let taskID = "com.dsh.agentlite.ios.refresh"

    /// 注册后台任务处理器。
    ///
    /// - Important: 必须在 App 启动完成前调用，且**只有一次**机会；
    ///   重复注册同一 identifier 会让后续调用返回 false。
    /// - Returns: 注册是否成功（模拟器/未开启后台能力时会失败，属预期）。
    @discardableResult
    static func register(model: AppModel) -> Bool {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: taskID,
            using: nil        // nil = 主队列；我们的处理逻辑本来就是 @MainActor
        ) { task in
            guard let refresh = task as? BGAppRefreshTask else {
                task.setTaskCompleted(success: false)
                return
            }
            handle(refresh: refresh, model: model)
        }
    }

    /// 申请下一次后台刷新。每次处理完都要重新申请 —— 这是 iOS 的契约。
    static func scheduleNext() {
        let request = BGAppRefreshTaskRequest(identifier: taskID)
        // 只是"最早可能"，不是保证。设太短反而会被系统降频。
        request.earliestBeginDate = Date(timeIntervalSinceNow: 15 * 60)
        do {
            try BGTaskScheduler.shared.submit(request)
        } catch {
            // 模拟器或未开启 Background Modes 时会失败，属预期，不当作错误抛出。
            print("[AgentLite] 后台刷新申请失败（模拟器常见，可忽略）: \(error)")
        }
    }

    private static func handle(refresh: BGAppRefreshTask, model: AppModel) {
        scheduleNext()

        // 预算：后台窗口有限，被强杀会丢 ack（下发方会以为没生效而重复下发）。
        // 与 CommandInbox.defaultBudget 一致 —— 宁可少做一条，也不要被强杀。
        let work = Task { @MainActor in
            await model.collect()
        }

        // 系统准备回收本任务时调用。必须取消工作并如实上报，
        // 否则会被记为"超时未完成"，后续调度更少。
        refresh.expirationHandler = {
            work.cancel()
        }

        Task { @MainActor in
            _ = await work.value
            refresh.setTaskCompleted(success: !work.isCancelled)
        }
    }
}
