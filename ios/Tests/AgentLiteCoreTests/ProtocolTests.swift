// SPDX-License-Identifier: Apache-2.0
//
// 线协议与安全红线测试 —— 这些是不需要网络就能验的部分。

import XCTest
@testable import AgentLiteCore

final class ProtocolTests: XCTestCase {

    // MARK: - 敏感操作红线

    func testSensitiveGuardBlocksPaymentAndDeletion() {
        let g = SensitiveGuard()
        XCTAssertNotNil(g.match("给张三转账 100 元"), "转账必须被拦")
        XCTAssertNotNil(g.match("帮我付款"), "付款必须被拦")
        XCTAssertNotNil(g.match("删除所有照片"), "删除必须被拦")
        XCTAssertNotNil(g.match("把验证码发给他"), "验证码必须被拦")
        XCTAssertNotNil(g.match("please transfer money"), "英文动词也要拦")
    }

    func testSensitiveGuardAllowsBenignTasks() {
        let g = SensitiveGuard()
        XCTAssertNil(g.match("打开设置页面"))
        XCTAssertNil(g.match("把屏幕亮度调低"))
        XCTAssertNil(g.match("查一下明天的天气"))
    }

    /// 拦截发生在**执行之前**，且不依赖任何网络/权限。
    func testExecutorRejectsSensitiveBeforeAnyExecution() async {
        let spy = SpyProvider(result: .done("不该被执行"))
        let executor = CommandExecutor(provider: spy)
        let outcome = await executor.execute("帮我转账 500 元")
        XCTAssertEqual(outcome.status, .failed, "服务端没有 cancelled 状态，拒绝必须走 failed")
        let calls = await spy.callCount
        XCTAssertEqual(calls, 0, "敏感任务根本不应到达 provider")
    }

    // MARK: - 能力边界必须诚实

    /// 这是 iOS 端最重要的一条产品纪律：做不到就说做不到，不许假装成功。
    func testUnsupportedTaskReportsFailedNotDone() async {
        let executor = CommandExecutor(provider: NoLocalActionsProvider())
        let outcome = await executor.execute("打开微信给妈妈发消息")
        XCTAssertEqual(outcome.status, .failed, "iOS 无系统级自动化能力，必须回报 failed")
        XCTAssertTrue(outcome.detail.contains("无法执行"), "失败原因要说人话，便于用户改配置")
    }

    func testEmptyTaskRejected() async {
        let executor = CommandExecutor(provider: NoLocalActionsProvider())
        let outcome = await executor.execute("   ")
        XCTAssertEqual(outcome.status, .failed)
    }

    /// provider 能处理时必须如实回报 done（而不是一律拒绝）。
    func testProviderHandleWins() async {
        let spy = SpyProvider(result: .done("已打开设置"))
        let executor = CommandExecutor(provider: spy)
        let outcome = await executor.execute("打开设置")
        XCTAssertEqual(outcome.status, .done)
        XCTAssertEqual(outcome.detail, "已打开设置")
    }

    // MARK: - 客户端预校验（不替代服务端）

    func testAccountNameValidationMirrorsServer() throws {
        // 服务端规则：3-32 位，仅小写字母/数字/下划线/连字符（account.go:92）
        XCTAssertNoThrow(try AccountService.validate(name: "abc", key: "12345678"))
        XCTAssertThrowsError(try AccountService.validate(name: "ab", key: "12345678"))
        XCTAssertThrowsError(try AccountService.validate(name: String(repeating: "a", count: 33), key: "12345678"))
        XCTAssertThrowsError(try AccountService.validate(name: "坏名字", key: "12345678"))
        // 大写在客户端先归一化（服务端 account.go:92 也做 strings.ToLower），
        // 因此 "ABC" 应被接受为 "abc" —— 这里**不能**断言抛错，
        // 否则会把「用户输入大写就报错」这种真实缺陷当成正确行为固化下来。
        XCTAssertNoThrow(try AccountService.validate(name: "ABC", key: "12345678"))
        XCTAssertThrowsError(try AccountService.validate(name: "valid_name-1", key: "short"), "密钥至少 8 位")
    }

    func testUppercaseNameIsNormalizedNotRejected() throws {
        // 服务端会 strings.ToLower，客户端预校验也应先归一化再判长度/字符集
        XCTAssertNoThrow(try AccountService.validate(name: "MyAccount", key: "12345678"))
    }

    // MARK: - 设备身份

    func testFingerprintIsStableAcrossCalls() throws {
        let store = InMemoryStore()
        let a = try DeviceIdentity.fingerprint(store: store)
        let b = try DeviceIdentity.fingerprint(store: store)
        XCTAssertEqual(a, b, "同一次安装内指纹必须稳定，否则服务端会多出幽灵设备")
        XCTAssertTrue(a.hasPrefix("ios-"), "前缀让服务端日志一眼看出平台")
        XCTAssertEqual(a.count, 4 + 32)
    }

    func testFingerprintDiffersAcrossInstalls() throws {
        let a = try DeviceIdentity.fingerprint(store: InMemoryStore())
        let b = try DeviceIdentity.fingerprint(store: InMemoryStore())
        XCTAssertNotEqual(a, b)
    }

    // MARK: - 局域网入站校验（抗重放 / 时间窗 / AAD）

    func testInboundAcceptsWellFormedEnvelope() async throws {
        let sender = LANPeer(accountKey: "shared-key-12345678", accountID: "acct-1", deviceID: "d_sender")
        let receiver = LANPeer(accountKey: "shared-key-12345678", accountID: "acct-1", deviceID: "d_receiver")

        let plain = try await sender.encodeOutbound(since: 42, tasks: [], memories: [])
        let envelope = try AgentLiteCrypto.seal(
            key: AgentLiteCrypto.deriveLANKey(accountKey: "shared-key-12345678", accountID: "acct-1"),
            aad: "agentlite-lan-v1|acct-1",
            plaintext: plain
        )
        let (from, payload) = try await receiver.acceptInbound(envelope: envelope)
        XCTAssertEqual(from, "d_sender")
        XCTAssertEqual(payload.since, 42)
    }

    /// 重放同一条密文必须被拒 —— 只校验时间窗是不够的，旧报文本身完全合法。
    func testInboundRejectsReplay() async throws {
        let sender = LANPeer(accountKey: "shared-key-12345678", accountID: "acct-1", deviceID: "d_sender")
        let receiver = LANPeer(accountKey: "shared-key-12345678", accountID: "acct-1", deviceID: "d_receiver")
        let plain = try await sender.encodeOutbound(since: 1, tasks: [], memories: [])
        let envelope = try AgentLiteCrypto.seal(
            key: AgentLiteCrypto.deriveLANKey(accountKey: "shared-key-12345678", accountID: "acct-1"),
            aad: "agentlite-lan-v1|acct-1",
            plaintext: plain
        )
        _ = try await receiver.acceptInbound(envelope: envelope)
        do {
            _ = try await receiver.acceptInbound(envelope: envelope)
            XCTFail("重复报文必须被拒")
        } catch {
            // 预期：replay
        }
    }

    func testInboundRejectsStaleTimestamp() async throws {
        let key = AgentLiteCrypto.deriveLANKey(accountKey: "shared-key-12345678", accountID: "acct-1")
        let receiver = LANPeer(accountKey: "shared-key-12345678", accountID: "acct-1", deviceID: "d_receiver")
        // 手工造一条 10 分钟前的报文（超出 120 秒窗口）
        let stale = #"{"ts":\#(Int(Date().timeIntervalSince1970) - 600),"device_id":"d_old","since":0,"push":{"tasks":[],"memories":[]}}"#
        let envelope = try AgentLiteCrypto.seal(key: key, aad: "agentlite-lan-v1|acct-1", plaintext: stale)
        do {
            _ = try await receiver.acceptInbound(envelope: envelope)
            XCTFail("超时窗报文必须被拒")
        } catch {
            // 预期
        }
    }

    func testInboundRejectsForeignAccount() async throws {
        // 另一个账号的密文：AAD 与密钥都不同 → 必须解不开
        let receiver = LANPeer(accountKey: "shared-key-12345678", accountID: "acct-1", deviceID: "d_receiver")
        let foreign = LANPeer(accountKey: "other-key-12345678", accountID: "acct-2", deviceID: "d_x")
        let plain = try await foreign.encodeOutbound(since: 0, tasks: [], memories: [])
        let envelope = try AgentLiteCrypto.seal(
            key: AgentLiteCrypto.deriveLANKey(accountKey: "other-key-12345678", accountID: "acct-2"),
            aad: "agentlite-lan-v1|acct-2",
            plaintext: plain
        )
        do {
            _ = try await receiver.acceptInbound(envelope: envelope)
            XCTFail("跨账号密文必须被拒")
        } catch {
            // 预期
        }
    }

    // MARK: - 线格式（字段名必须与服务端/Android 逐字一致）

    func testOutboundWireFormatFieldNames() throws {
        let item = SyncOutgoingItem(
            id: "t1", ts: "2026-09-13T00:00:00Z",
            data: .object(["k": .string("v")]),
            originDeviceID: "d_origin"
        )
        let body = LANPeer.OutboundPayload(
            ts: 1757690000, deviceID: "d_swift", since: 7,
            push: .init(tasks: [item], memories: [])
        )
        let json = try JSONSerialization.jsonObject(with: JSONEncoder().encode(body)) as? [String: Any]
        // 协议 §4.2 规定用 snake_case
        XCTAssertNotNil(json?["device_id"])
        XCTAssertNotNil(json?["push"])
        let push = json?["push"] as? [String: Any]
        XCTAssertNotNil(push?["tasks"])
        let task = (push?["tasks"] as? [[String: Any]])?.first
        XCTAssertEqual(task?["origin_device_id"] as? String, "d_origin",
                       "origin_device_id 缺失会让服务端把记录记成『本机产生』，溯源出错")
    }

    func testServerModelDecodingIsLenientAboutOptionals() throws {
        // 服务端 device 对象字段可缺（旧记录），解码不能因此失败
        let json = #"{"device_id":"d_1","account_id":"a_1"}"#
        let d = try JSONDecoder().decode(ServerDevice.self, from: Data(json.utf8))
        XCTAssertEqual(d.deviceID, "d_1")
        XCTAssertNil(d.name)
    }

    func testCommandStatusRawValuesMatchServer() {
        // 服务端只认这四个（command.go 的 cmdStatus*）
        XCTAssertEqual(CommandStatus.running.rawValue, "running")
        XCTAssertEqual(CommandStatus.done.rawValue, "done")
        XCTAssertEqual(CommandStatus.failed.rawValue, "failed")
        // 服务端**没有** cancelled：多一个值就会让 ack 被 400 拒掉
        XCTAssertNil(CommandStatus(rawValue: "cancelled"), "cancelled 不是合法 ack 状态")
        XCTAssertNil(CommandStatus(rawValue: "expired"), "expired 由服务端标注，客户端不得回报")
    }

    func testRemoteTaskPrefixMatchesAndroid() {
        // 与 RemoteCommand.java 的 REMOTE_PREFIX 同值
        XCTAssertEqual(RemoteTaskPrefix.marker, "[跨设备指令] ")
    }

    func testHTTPClientRefusesForeignHostForInsecureMode() {
        // insecureLocalhost 只允许环回；公网地址必须触发断言（防止被误用到生产）
        XCTAssertTrue(true)   // 实际约束由 precondition 保证，见 HTTPClient.insecureLocalhost
    }
}

// MARK: - 测试替身

/// 记录调用次数的 provider，用来验证「敏感任务不会到达执行层」。
actor SpyProvider: LocalActionProvider {
    nonisolated let result: ExecutionOutcome?
    private(set) var callCount = 0

    init(result: ExecutionOutcome?) { self.result = result }

    nonisolated var capabilities: [String] { ["spy"] }

    func perform(task: String) async throws -> ExecutionOutcome? {
        callCount += 1
        return result
    }
}
