// SPDX-License-Identifier: Apache-2.0
//
// 跨设备指令（远程控制的服务端半环）。
//
// 场景：iPhone 上选一台同账号的 Android 设备 → 下发自然语言任务 → 那台设备执行 → 回报结果。
// 服务端是**拉取式**队列（command.go 头注释）：5 分钟未领取即过期，不支持广播，
// 只有目标设备能 ack —— iOS 端因此要做的三件事：
//   1. 下发前先 `GET /account/me` 拿设备列表（用户选目标）；
//   2. 目标设备定时 `GET /device/commands` 领取（iOS 前台 + BGAppRefresh 触发）；
//   3. 执行完 `POST /device/command/ack` 回报终态。
//
// 为什么 iOS 端必须容忍「领不到指令」：iOS 后台刷新由系统调度（可能几十分钟不触发），
// 而指令 TTL 只有 5 分钟。所以 iOS 作为**目标设备**的可靠性天然低于 Android
// ——这一点必须写进 UI 预期，而不是假装能和 Android 一样实时（详见手册 §5.3）。

import Foundation

public actor CommandService {
    private let http: HTTPClient

    public init(http: HTTPClient) {
        self.http = http
    }

    // MARK: - 下发方

    /// 下发一条指令到同账号的另一台设备。
    /// - Parameter task: 自然语言任务文本（服务端限制 512 字符，见 maxCommandTaskLen）。
    public func send(toDeviceID: String, task: String) async throws -> SyncedCommand {
        let trimmed = task.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else {
            throw AgentLiteError.server(ServerError(status: 400, code: "bad_input", message: "任务内容不能为空"))
        }
        guard trimmed.count <= 512 else {
            throw AgentLiteError.server(ServerError(status: 400, code: "bad_input", message: "任务内容过长（上限 512 字符）"))
        }
        let env: CommandEnvelope = try await http.send(
            method: "POST",
            path: "/api/v1/device/command",
            body: CreateCommandRequest(toDevice: toDeviceID, task: trimmed),
            options: .init(deviceScoped: true),
            as: CommandEnvelope.self
        )
        return env.command
    }

    private struct CreateCommandRequest: Encodable {
        let toDevice: String
        let task: String
        enum CodingKeys: String, CodingKey {
            case toDevice = "to_device"
            case task
        }
    }

    // MARK: - 接收方

    /// 领取本机待执行的指令。
    public func pending() async throws -> [SyncedCommand] {
        let res: CommandListResult = try await http.send(
            method: "GET",
            path: "/api/v1/device/commands",
            body: EmptyBody?.none,
            options: .init(deviceScoped: true),
            as: CommandListResult.self
        )
        return res.commands
    }

    /// 回报终态。只有目标设备能回报（服务端 ErrNotTargetDevice）。
    /// - Important: `status` 只能取 running/done/failed（服务端 command.go:166-171）。
    ///   `CommandStatus` 这个类型已经把非法值挡在外面了。
    public func ack(commandID: String, status: CommandStatus, detail: String) async throws -> SyncedCommand {
        let env: CommandEnvelope = try await http.send(
            method: "POST",
            path: "/api/v1/device/command/ack",
            body: AckRequest(commandID: commandID, status: status.rawValue, detail: String(detail.prefix(500))),
            options: .init(deviceScoped: true),
            as: CommandEnvelope.self
        )
        return env.command
    }

    private struct AckRequest: Encodable {
        let commandID: String
        let status: String
        let detail: String
        enum CodingKeys: String, CodingKey {
            case commandID = "command_id"
            case status, detail
        }
    }

    // MARK: - 下发方查结果

    public func result(commandID: String) async throws -> SyncedCommand {
        let env: CommandEnvelope = try await http.send(
            method: "GET",
            path: "/api/v1/device/command",
            body: EmptyBody?.none,
            options: .init(deviceScoped: false, query: ["id": commandID]),
            as: CommandEnvelope.self
        )
        return env.command
    }

    /// 轮询单条指令直到它离开「未终态」或超时。
    ///
    /// ★ **重要边界（实测得出）**：下发方**无法**观察到已终态的指令。
    /// 服务端在目标设备回报终态后就把它从内存表删除（`command.go:195`），
    /// 此后查询一律 404。因此本方法有三种可能的返回：
    ///   1. 指令仍是 `running` 超时 → 返回最后一次快照（此时 404 意味着**对方已完成**）；
    ///   2. 中途拿到 `expired` → 服务端标注的终态；
    ///   3. 拿到 404 → 抛 `.server(404)`，语义是「结果已不存在（多半是已完成）」。
    ///
    /// 想让下发方可靠拿到结果，**必须由目标设备在 ack 之外另发一条同步记录**
    /// （走 `/sync/push`）。当前服务端未提供该回执通道 —— 已记为未完成项（手册 §9, U3）。
    public func waitForResult(
        commandID: String,
        timeout: TimeInterval = 300,
        interval: TimeInterval = 3,
        onTick: (@Sendable (SyncedCommand) -> Void)? = nil
    ) async throws -> SyncedCommand {
        let deadline = Date().addingTimeInterval(timeout)
        var last: SyncedCommand?
        while Date() < deadline {
            do {
                let c = try await result(commandID: commandID)
                last = c
                onTick?(c)
                // 终态集合来自服务端状态机；expired 是服务端自己标的（超 TTL 未领取）
                if ["done", "failed", "expired"].contains(c.status) {
                    return c
                }
            } catch let e as AgentLiteError {
                // 404 = 指令已不在服务端内存表：要么过期被回收、要么对方已回报终态后被删除。
                // 两种情况都**没有可观察的后续**了，立即收敛而不是空转到超时。
                if case .server(let se) = e, se.status == 404 {
                    throw e
                }
                // 其余（网络抖动）忽略，继续等
            }
            try await Task.sleep(nanoseconds: UInt64(interval * 1_000_000_000))
        }
        if let last { return last }
        throw AgentLiteError.transport("等待指令结果超时（\(Int(timeout)) 秒）")
    }
}
