// SPDX-License-Identifier: Apache-2.0
//
// 实际 HTTP 请求经过 HTTPClient 的编码、鉴权和解析；URLProtocol 只替换传输。
// 无服务端环境变量依赖，覆盖分页消费、账号隔离以及档案刷新请求次数。

import XCTest
@testable import AgentLiteCore

final class SyncServiceTests: XCTestCase {
    private func service(
        count: Int = 1001,
        nextSince: Bool = true,
        store: SecureStore = InMemoryStore()
    ) async -> (SyncService, HTTPFixture) {
        let fixture = HTTPFixture { request in
            let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)!.queryItems ?? []
            let since = Int64(query.first { $0.name == "since" }?.value ?? "0")!
            let limit = Int(query.first { $0.name == "limit" }?.value ?? "200")!
            let records = (1...max(1, count)).compactMap { index -> SyncRecord? in
                guard index <= count, Int64(index) > since else { return nil }
                return syncRecord(Int64(index))
            }
            let page = Array(records.prefix(max(0, limit)))
            return try .json(PullResult(
                records: page,
                serverSeq: Int64(count),
                hasMore: page.count < records.count,
                nextSince: nextSince ? (page.last?.seq ?? since) : nil
            ))
        }
        await fixture.http.setAuth(token: "token-a", deviceID: "d_self", accountID: "a_1")
        return (SyncService(http: fixture.http, cursorStore: store), fixture)
    }

    func testConsumes1001RecordsInSixPagesWithoutSkipping() async throws {
        let (sync, fixture) = await service()
        let consumed = SyncTestConsumer()
        let records = try await sync.drain(limit: 200, consume: { page in
            // 数据交付时，该页尚未提交游标。
            let current = try await sync.cursor()
            XCTAssertEqual(current, page.first!.seq - 1)
            await consumed.append(page)
        })
        let saved = await consumed.records
        XCTAssertEqual(saved.map(\.seq), Array(1...1001).map(Int64.init))
        XCTAssertEqual(records, saved)
        let cursor = try await sync.cursor()
        XCTAssertEqual(cursor, 1001)
        XCTAssertEqual(fixture.requests.count, 6)
        XCTAssertEqual(fixture.requests.map { queryValue("since", in: $0) }, ["0", "200", "400", "600", "800", "1000"])
        XCTAssertTrue(fixture.requests.allSatisfy { queryValue("limit", in: $0) == "200" })
        XCTAssertTrue(fixture.requests.allSatisfy { $0.value(forHTTPHeaderField: "X-Device-Id") == "d_self" })
    }

    func testOldServerHighWaterDoesNotBecomeConsumptionCursor() async throws {
        let (sync, fixture) = await service(nextSince: false)
        let consumed = SyncTestConsumer()
        let first = try await sync.pull(limit: 200, consume: { await consumed.append($0) })
        XCTAssertNil(first.nextSince)
        XCTAssertEqual(first.serverSeq, 1001)
        let cursor = try await sync.cursor()
        XCTAssertEqual(cursor, 200)
        _ = try await sync.drain(limit: 200, consume: { await consumed.append($0) })
        let saved = await consumed.records
        XCTAssertEqual(saved.map(\.seq), Array(1...1001).map(Int64.init))
        XCTAssertEqual(fixture.requests.map { queryValue("since", in: $0) }, ["0", "200", "400", "600", "800", "1000"])
    }

    func testLegacyPullAndDrainFetchWithoutCommitting() async throws {
        let (sync, fixture) = await service(nextSince: false)
        let first = try await sync.pull(limit: 2)
        XCTAssertEqual(first.records.map(\.seq), [1, 2])
        var cursor = try await sync.cursor()
        XCTAssertEqual(cursor, 0)
        let all = try await sync.drain(limit: 200)
        XCTAssertEqual(all.map(\.seq), Array(1...1001).map(Int64.init))
        cursor = try await sync.cursor()
        XCTAssertEqual(cursor, 0, "返回记录不等于调用方已消费记录")
        let repeated = try await sync.pull(limit: 2)
        XCTAssertEqual(repeated, first)
        XCTAssertEqual(fixture.requests.map { queryValue("since", in: $0) }, ["0", "0", "200", "400", "600", "800", "1000", "0"])
    }

    func testConsumerFailureLeavesPageAvailableForRetry() async throws {
        let (sync, fixture) = await service()
        do {
            _ = try await sync.pull(consume: { _ in throw FixtureError.consumption })
            XCTFail("消费失败应传回调用方")
        } catch FixtureError.consumption { }
        var cursor = try await sync.cursor()
        XCTAssertEqual(cursor, 0)
        let consumed = SyncTestConsumer()
        _ = try await sync.pull(consume: { await consumed.append($0) })
        cursor = try await sync.cursor()
        XCTAssertEqual(cursor, 200)
        XCTAssertEqual(fixture.requests.map { queryValue("since", in: $0) }, ["0", "0"])
    }

    func testFailedSecondPageResumesAfterFirstConsumedPage() async throws {
        let (sync, fixture) = await service()
        let consumed = SyncTestConsumer()
        do {
            _ = try await sync.drain(consume: { page in
                if page.first?.seq == 201 { throw FixtureError.consumption }
                await consumed.append(page)
            })
            XCTFail("第二页消费失败应停止本次分页")
        } catch FixtureError.consumption { }
        let cursor = try await sync.cursor()
        XCTAssertEqual(cursor, 200)
        _ = try await sync.drain(consume: { await consumed.append($0) })
        let saved = await consumed.records
        XCTAssertEqual(saved.map(\.seq), Array(1...1001).map(Int64.init))
        XCTAssertEqual(fixture.requests.map { queryValue("since", in: $0) }, ["0", "200", "200", "400", "600", "800", "1000"])
    }

    func testAccountCursorsAreIsolatedAndLegacyCursorIsNotAdopted() async throws {
        let store = InMemoryStore()
        try store.set(Data("99999".utf8), for: "sync.cursor.v1")
        let (sync, fixture) = await service(count: 5, store: store)
        let consumed = SyncTestConsumer()
        var cursor = try await sync.cursor()
        XCTAssertEqual(cursor, 0, "没有账号归属的旧游标应重放而非继承")
        _ = try await sync.pull(limit: 2, consume: { await consumed.append($0) })
        await fixture.http.setAuth(token: "token-b", deviceID: "d_self", accountID: "a_2")
        cursor = try await sync.cursor()
        XCTAssertEqual(cursor, 0)
        _ = try await sync.pull(limit: 1, consume: { await consumed.append($0) })
        cursor = try await sync.cursor()
        XCTAssertEqual(cursor, 1)
        try await sync.resetCursor()
        await fixture.http.setAuth(token: "token-a", deviceID: "d_self", accountID: "a_1")
        cursor = try await sync.cursor()
        XCTAssertEqual(cursor, 2, "重置另一账号游标不影响本账号")
        let next = try await sync.pull(limit: 1)
        XCTAssertEqual(next.records.first?.seq, 3)
        XCTAssertEqual(fixture.requests.map { queryValue("since", in: $0) }, ["0", "0", "2"])
    }

    func testAccountChangeDuringConsumptionDoesNotCommitToEitherAccount() async throws {
        let (sync, fixture) = await service()
        do {
            _ = try await sync.pull(consume: { _ in
                await fixture.http.setAuth(token: "token-b", deviceID: "d_self", accountID: "a_2")
            })
            XCTFail("消费过程中切换账号应终止游标提交")
        } catch AgentLiteError.sessionChanged { }
        var cursor = try await sync.cursor()
        XCTAssertEqual(cursor, 0)
        await fixture.http.setAuth(token: "token-a", deviceID: "d_self", accountID: "a_1")
        cursor = try await sync.cursor()
        XCTAssertEqual(cursor, 0)
    }

    func testSwitchAwayAndBackDuringConsumptionInvalidatesOriginalSession() async throws {
        let (sync, fixture) = await service()
        do {
            _ = try await sync.pull(consume: { _ in
                await fixture.http.setAuth(token: "token-b", deviceID: "d_self", accountID: "a_2")
                await fixture.http.setAuth(token: "token-a", deviceID: "d_self", accountID: "a_1")
            })
            XCTFail("账号ID相同也不代表仍是发起同步的会话")
        } catch AgentLiteError.sessionChanged { }
        let cursor = try await sync.cursor()
        XCTAssertEqual(cursor, 0)
    }

    func testAccountChangeWhileRequestIsInFlightDoesNotDeliverOldRecords() async throws {
        let started = expectation(description: "pull request started")
        let release = DispatchSemaphore(value: 0)
        let fixture = HTTPFixture { _ in
            started.fulfill()
            guard release.wait(timeout: .now() + 5) == .success else { throw FixtureError.transport }
            return try .json(PullResult(records: [syncRecord(1)], serverSeq: 50, hasMore: false, nextSince: 1))
        }
        defer { release.signal() }
        let consumed = SyncTestConsumer()
        let store = InMemoryStore()
        let sync = SyncService(http: fixture.http, cursorStore: store)
        await fixture.http.setAuth(token: "token-a", deviceID: "d_self", accountID: "a_1")
        let request = Task { try await sync.pull(consume: { await consumed.append($0) }) }
        await fulfillment(of: [started], timeout: 5)
        await fixture.http.setAuth(token: "token-b", deviceID: "d_self", accountID: "a_2")
        release.signal()
        do {
            _ = try await request.value
            XCTFail("旧账号的网络响应应在消费前被隔离")
        } catch AgentLiteError.sessionChanged { }
        let records = await consumed.records
        XCTAssertTrue(records.isEmpty)
        let cursor = try await sync.cursor()
        XCTAssertEqual(cursor, 0)
        XCTAssertNil(try store.get("sync.cursor.v2.a_1"))
        XCTAssertEqual(fixture.requests.first?.value(forHTTPHeaderField: "Authorization"), "Bearer token-a")
    }

    func testEmptyPageDoesNotAdvanceToServerHighWater() async throws {
        let fixture = HTTPFixture { _ in
            try .json(PullResult(records: [], serverSeq: 1001, hasMore: false, nextSince: 0))
        }
        await fixture.http.setAuth(token: "token-a", deviceID: "d_self", accountID: "a_1")
        let sync = SyncService(http: fixture.http, cursorStore: InMemoryStore())
        let consumed = SyncTestConsumer()
        _ = try await sync.pull(consume: { await consumed.append($0) })
        let cursor = try await sync.cursor()
        let count = await consumed.calls
        XCTAssertEqual(cursor, 0)
        XCTAssertEqual(count, 0)
    }

    func testBadDeliveryCursorIsRejectedBeforeConsumption() async throws {
        let fixture = HTTPFixture { _ in
            try .json(PullResult(records: [syncRecord(1)], serverSeq: 1001, hasMore: true, nextSince: 1001))
        }
        await fixture.http.setAuth(token: "token-a", deviceID: "d_self", accountID: "a_1")
        let sync = SyncService(http: fixture.http, cursorStore: InMemoryStore())
        let consumed = SyncTestConsumer()
        do {
            _ = try await sync.pull(consume: { await consumed.append($0) })
            XCTFail("next_since 高于本页记录应报契约错误")
        } catch AgentLiteError.decoding { }
        let cursor = try await sync.cursor()
        let count = await consumed.calls
        XCTAssertEqual(cursor, 0)
        XCTAssertEqual(count, 0)
    }

    func testCursorWriteFailureDoesNotLosePage() async throws {
        let store = FailingCursorStore()
        let (sync, fixture) = await service(store: store)
        let consumed = SyncTestConsumer()
        do {
            _ = try await sync.pull(consume: { await consumed.append($0) })
            XCTFail("持久化游标失败应报错，后续重放由消费方幂等处理")
        } catch FixtureError.storage { }
        var cursor = try await sync.cursor()
        XCTAssertEqual(cursor, 0)
        store.allowCursorWrites()
        _ = try await sync.pull(consume: { await consumed.append($0) })
        cursor = try await sync.cursor()
        XCTAssertEqual(cursor, 200)
        XCTAssertEqual(fixture.requests.map { queryValue("since", in: $0) }, ["0", "0"])
    }

    func testProfileRefreshSharesDevicesFromSingleResponse() async throws {
        let fixture = HTTPFixture { request in
            XCTAssertEqual(request.url?.path, "/api/v1/account/me")
            return .init(status: 200, data: Data(#"{"account_id":"a_1","name":"alice","quota":100,"quota_used":7,"devices":[{"device_id":"d_self","account_id":"a_1"},{"device_id":"d_peer","account_id":"a_1"}]}"#.utf8))
        }
        let store = InMemoryStore()
        let account = AccountService(http: fixture.http, credentials: CredentialStore(store: store), secureStore: store)
        await fixture.http.setAuth(token: "token-a", deviceID: "d_self", accountID: "a_1")
        // 与 AppModel.refreshProfile 一致：一份响应同时驱动额度与对端设备。
        let profile = try await account.profile()
        let localDeviceID = await fixture.http.currentDeviceID
        let devices = profile.peerDevices(excluding: localDeviceID)
        XCTAssertEqual(profile.quota, 100)
        XCTAssertEqual(profile.quotaUsed, 7)
        XCTAssertEqual(devices.map(\.deviceID), ["d_peer"])
        XCTAssertEqual(profile.devices.map(\.deviceID), ["d_self", "d_peer"], "筛选不改写原档案")
        XCTAssertEqual(fixture.requests.count, 1)
    }

    func testLegacyPeerDevicesStillMakesOneProfileRequest() async throws {
        let fixture = HTTPFixture { _ in
            .init(status: 200, data: Data(#"{"account_id":"a_1","name":"alice","quota":100,"quota_used":7,"devices":[{"device_id":"d_self","account_id":"a_1"},{"device_id":"d_peer","account_id":"a_1"}]}"#.utf8))
        }
        let store = InMemoryStore()
        let account = AccountService(http: fixture.http, credentials: CredentialStore(store: store), secureStore: store)
        await fixture.http.setAuth(token: "token-a", deviceID: "d_self", accountID: "a_1")
        let devices = try await account.peerDevices()
        XCTAssertEqual(devices.map(\.deviceID), ["d_peer"])
        XCTAssertEqual(fixture.requests.count, 1)
    }

    func testLogoutClearsSessionBeforeReturning() async throws {
        let fixture = HTTPFixture { _ in throw FixtureError.transport }
        let store = InMemoryStore()
        let credentials = CredentialStore(store: store)
        try credentials.save(.init(accountID: "a_1", accountName: "alice", token: "token-a", deviceID: "d_self", accountKey: "test-key"))
        let account = AccountService(http: fixture.http, credentials: credentials, secureStore: store)
        _ = try await account.restore()
        try await account.logout()
        let token = await fixture.http.currentToken
        XCTAssertNil(token)
        XCTAssertNil(try credentials.load())
        let sync = SyncService(http: fixture.http, cursorStore: store)
        do {
            _ = try await sync.cursor()
            XCTFail("退出后没有当前账号游标")
        } catch AgentLiteError.notLoggedIn { }
        XCTAssertTrue(fixture.requests.isEmpty)
    }
}

actor SyncTestConsumer {
    private(set) var records: [SyncRecord] = []
    private(set) var calls = 0

    func append(_ page: [SyncRecord]) {
        calls += 1
        records.append(contentsOf: page)
    }
}

private enum FixtureError: Error { case consumption, transport, storage }

private func syncRecord(_ seq: Int64) -> SyncRecord {
    SyncRecord(seq: seq, deviceID: "d_peer", kind: "task", id: "task-\(seq)", ts: "2026-10-10T00:00:00Z", data: .object(["seq": .number(Double(seq))]))
}

private func queryValue(_ key: String, in request: URLRequest) -> String? {
    URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems?.first { $0.name == key }?.value
}

private final class FailingCursorStore: SecureStore, @unchecked Sendable {
    private let store = InMemoryStore()
    private let lock = NSLock()
    private var failWrites = true

    func allowCursorWrites() {
        lock.lock(); defer { lock.unlock() }
        failWrites = false
    }

    func set(_ data: Data, for key: String) throws {
        lock.lock(); defer { lock.unlock() }
        if failWrites && key.hasPrefix("sync.cursor.v2.") { throw FixtureError.storage }
        try store.set(data, for: key)
    }

    func get(_ key: String) throws -> Data? { try store.get(key) }
    func remove(_ key: String) throws { try store.remove(key) }
}

private final class HTTPFixture: @unchecked Sendable {
    struct Reply {
        let status: Int
        let data: Data

        static func json<T: Encodable>(_ value: T) throws -> Reply {
            Reply(status: 200, data: try JSONEncoder().encode(value))
        }
    }

    let http: HTTPClient
    private let session: URLSession
    private let host: String
    private let handler: @Sendable (URLRequest) throws -> Reply
    private let lock = NSLock()
    private var log: [URLRequest] = []

    var requests: [URLRequest] {
        lock.lock(); defer { lock.unlock() }
        return log
    }

    init(handler: @escaping @Sendable (URLRequest) throws -> Reply) {
        self.handler = handler
        host = UUID().uuidString.lowercased() + ".test"
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [FixtureURLProtocol.self]
        session = URLSession(configuration: config)
        http = HTTPClient(baseURL: URL(string: "https://\(host)")!, session: session)
        FixtureURLProtocol.register(host: host) { [weak self] request in
            guard let self else { throw FixtureError.transport }
            self.lock.lock()
            self.log.append(request)
            self.lock.unlock()
            return try self.handler(request)
        }
    }

    deinit {
        FixtureURLProtocol.remove(host: host)
        session.invalidateAndCancel()
    }
}

private final class FixtureURLProtocol: URLProtocol, @unchecked Sendable {
    private static let lock = NSLock()
    private static var handlers: [String: @Sendable (URLRequest) throws -> HTTPFixture.Reply] = [:]

    static func register(host: String, handler: @escaping @Sendable (URLRequest) throws -> HTTPFixture.Reply) {
        lock.lock(); defer { lock.unlock() }
        handlers[host] = handler
    }

    static func remove(host: String) {
        lock.lock(); defer { lock.unlock() }
        handlers.removeValue(forKey: host)
    }

    override class func canInit(with request: URLRequest) -> Bool { request.url?.host?.hasSuffix(".test") == true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }

    override func startLoading() {
        Self.lock.lock()
        let handler = Self.handlers[request.url?.host ?? ""]
        Self.lock.unlock()
        // 不占用 URLSession 的协议队列，让在途账号切换测试能独立放行响应。
        DispatchQueue.global().async { [self] in
            do {
                guard let handler else { throw FixtureError.transport }
                let reply = try handler(request)
                let response = HTTPURLResponse(url: request.url!, statusCode: reply.status, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "application/json"])!
                client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
                client?.urlProtocol(self, didLoad: reply.data)
                client?.urlProtocolDidFinishLoading(self)
            } catch {
                client?.urlProtocol(self, didFailWithError: error)
            }
        }
    }

    override func stopLoading() { }
}
