// SPDX-License-Identifier: Apache-2.0
//
// 信息同步（服务端流）。契约见 agentlite-server/main.go:97-98：
//   POST /api/v1/sync/push  → {accepted, rejected, next_seq}
//   GET  /api/v1/sync/pull?since=<seq>&limit=<n> → {records, next_seq, has_more}
//
// 关键设计（照抄服务端取舍，不自行发明）：
//   - `seq` 由服务端**按账号**单调分配，客户端只把它当游标。绝不能拿本地时间当游标
//     ——多设备时钟不同步，会静默丢记录。
//   - `data` 是不透明 JSON，服务端不解释。因此 iOS 端也**原样保存**，
//     只保证「从哪台设备来、什么类型、什么 id」这三件事不丢。
//   - push 前必须带 `origin_device_id`：否则服务端会记成「本机产生」，
//     数据不丢但溯源错了（SyncClient.java:451 记录过这个坑）。

import Foundation

public actor SyncService {
    private let http: HTTPClient
    private let cursorStore: SecureStore
    private static let cursorKey = "sync.cursor.v1"

    public init(http: HTTPClient, cursorStore: SecureStore) {
        self.http = http
        self.cursorStore = cursorStore
    }

    // MARK: - 游标

    /// 已拉取到的位置。持久化到 Keychain 而不是 UserDefaults：
    /// 它决定了「哪些记录已经收过」，丢了会导致重复处理下行记录。
    public func cursor() throws -> Int64 {
        guard let d = try cursorStore.get(Self.cursorKey),
              let s = String(data: d, encoding: .utf8),
              let v = Int64(s) else { return 0 }
        return v
    }

    private func saveCursor(_ v: Int64) throws {
        try cursorStore.set(Data("\(v)".utf8), for: Self.cursorKey)
    }

    public func resetCursor() throws {
        try cursorStore.remove(Self.cursorKey)
    }

    // MARK: - 上行

    public func push(
        tasks: [SyncOutgoingItem] = [],
        memories: [SyncOutgoingItem] = [],
        status: JSONValue? = nil
    ) async throws -> PushResult {
        try await http.send(
            method: "POST",
            path: "/api/v1/sync/push",
            body: PushRequest(tasks: tasks, memories: memories, status: status),
            options: .init(deviceScoped: true),
            as: PushResult.self
        )
    }

    private struct PushRequest: Encodable {
        let tasks: [SyncOutgoingItem]
        let memories: [SyncOutgoingItem]
        let status: JSONValue?
    }

    // MARK: - 下行

    /// 拉取一次增量。**自动推进游标**，调用方不必关心游标管理。
    @discardableResult
    public func pull(limit: Int = 200) async throws -> PullResult {
        let since = try cursor()
        let res: PullResult = try await http.send(
            method: "GET",
            path: "/api/v1/sync/pull",
            body: EmptyBody?.none,
            options: .init(deviceScoped: true, query: ["since": "\(since)", "limit": "\(limit)"]),
            as: PullResult.self
        )
        try saveCursor(res.serverSeq)
        return res
    }

    /// 反复拉取直到服务端说没有更多。
    ///
    /// - Parameter maxRounds: 防御性上限。服务端若因缺陷永远回 `has_more=true`，
    ///   没有上限的循环会把电量烧干（iOS 后台预算尤其经不起这个）。
    public func drain(maxRounds: Int = 20, limit: Int = 200) async throws -> [SyncRecord] {
        var all: [SyncRecord] = []
        for _ in 0..<maxRounds {
            let res = try await pull(limit: limit)
            all.append(contentsOf: res.records)
            if !res.hasMore || res.records.isEmpty { break }
        }
        return all
    }

    /// 服务端 `/api/v1/config` 下发的配置（若有）。
    public func serverConfig() async throws -> JSONValue {
        try await http.send(method: "GET", path: "/api/v1/config", body: EmptyBody?.none, as: JSONValue.self)
    }
}
