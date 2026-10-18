// SPDX-License-Identifier: Apache-2.0
//
// 信息同步（服务端流）。
//   POST /api/v1/sync/push → {accepted, duplicates, rejected, server_seq}
//   GET /api/v1/sync/pull?since=<seq>&limit=<n> → {records, server_seq, next_since?, has_more}
//
// seq 在账号内单调递增。server_seq 是高水位，不是消费位置；只在消费完成后
// 保存实际记录的最后 seq。data 为不透明 JSON，客户端保留全部字段。

import Foundation

public actor SyncService {
    private let http: HTTPClient
    private let cursorStore: SecureStore

    public init(http: HTTPClient, cursorStore: SecureStore) {
        self.http = http
        self.cursorStore = cursorStore
    }

    // MARK: - 游标

    /// 当前账号已经成功消费的位置。旧版无账号归属的 sync.cursor.v1 不作迁移，
    /// 以免继承其它账号的高水位；首次升级会从 0 重放，消费方需按记录 ID 幂等保存。
    public func cursor() async throws -> Int64 {
        let session = try await http.syncSession()
        return try cursor(for: session.accountID)
    }

    private func cursorKey(for accountID: String) -> String {
        "sync.cursor.v2.\(accountID)"
    }

    private func cursor(for accountID: String) throws -> Int64 {
        guard let data = try cursorStore.get(cursorKey(for: accountID)),
              let value = String(data: data, encoding: .utf8).flatMap(Int64.init),
              value >= 0 else { return 0 }
        return value
    }

    private func saveCursor(_ value: Int64, for accountID: String) throws {
        // 并发消费同一页时，较迟完成的旧页也不回退已经成功消费的位置。
        let consumed = max(try cursor(for: accountID), value)
        try cursorStore.set(Data("\(consumed)".utf8), for: cursorKey(for: accountID))
    }

    public func resetCursor() async throws {
        let session = try await http.syncSession()
        try cursorStore.remove(cursorKey(for: session.accountID))
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

    /// 拉取一页。传入 consume 后，只有消费闭包成功返回才持久化该页游标。
    /// 闭包需完成该页全部记录的幂等持久化，不要只将工作放入异步队列后返回。
    /// 保留旧 pull(limit:) 调用方式，但纯拉取不代表消费，不再自动推进游标。
    @discardableResult
    public func pull(
        limit: Int = 200,
        consume: (@Sendable ([SyncRecord]) async throws -> Void)? = nil
    ) async throws -> PullResult {
        let session = try await http.syncSession()
        let since = try cursor(for: session.accountID)
        return try await pull(since: since, limit: limit, session: session, consume: consume)
    }

    /// 按页消费并提交；某页失败时保留此前成功页的位置，重试从失败页开始。
    /// 旧 drain(maxRounds:limit:) 仍返回完整记录，用局部游标分页，但不持久化。
    /// maxRounds 是一次调用的分页预算，达到预算后后续调用可接续已消费位置。
    public func drain(
        maxRounds: Int = 20,
        limit: Int = 200,
        consume: (@Sendable ([SyncRecord]) async throws -> Void)? = nil
    ) async throws -> [SyncRecord] {
        let session = try await http.syncSession()
        var since = try cursor(for: session.accountID)
        var all: [SyncRecord] = []
        for _ in 0..<max(0, maxRounds) {
            let result = try await pull(since: since, limit: limit, session: session, consume: consume)
            all.append(contentsOf: result.records)
            guard let last = result.records.last else { break }
            since = last.seq
            if !result.hasMore { break }
        }
        return all
    }

    private func pull(
        since: Int64,
        limit: Int,
        session: HTTPClient.SyncSession,
        consume: (@Sendable ([SyncRecord]) async throws -> Void)?
    ) async throws -> PullResult {
        var options = HTTPClient.Options(
            deviceScoped: true,
            query: ["since": "\(since)", "limit": "\(limit)"]
        )
        options.syncSession = session
        let result: PullResult = try await http.send(
            method: "GET",
            path: "/api/v1/sync/pull",
            body: EmptyBody?.none,
            options: options,
            as: PullResult.self
        )
        // 发出请求前及返回后均校验同一会话；切账号时不给消费方旧账号的记录。
        try await http.requireSession(session)

        var delivered = since
        for record in result.records {
            guard record.seq > delivered else {
                throw AgentLiteError.decoding("同步记录 seq 未按账号游标递增")
            }
            delivered = record.seq
        }
        // 旧服务端没有 next_since；新版字段只校验与实际已交付记录一致，
        // 不用它代替消费事实，更不把 server_seq 高水位当成下一页起点。
        if let nextSince = result.nextSince, nextSince != delivered {
            throw AgentLiteError.decoding("同步 next_since 与本页已交付记录不一致")
        }
        if result.hasMore && result.records.isEmpty {
            throw AgentLiteError.decoding("同步 has_more 为真但本页没有记录")
        }

        if let consume, !result.records.isEmpty {
            try await consume(result.records)
            try Task.checkCancellation()
            try await http.requireSession(session)
            try saveCursor(delivered, for: session.accountID)
        }
        return result
    }

    /// 服务端 `/api/v1/config` 下发的配置（若有）。
    public func serverConfig() async throws -> JSONValue {
        try await http.send(method: "GET", path: "/api/v1/config", body: EmptyBody?.none, as: JSONValue.self)
    }
}
