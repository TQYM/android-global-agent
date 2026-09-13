// SPDX-License-Identifier: Apache-2.0
//
// 端到端测试：**真连服务端**跑完整跨设备链路。
//
// 为什么要有这一层（而不只是单测）：单测只能证明「我按协议算对了」，
// 证明不了「服务端认我的请求」。iOS 端联调成本最高（装机/签名），
// 所以把可自动化的部分全部前移到 Mac 上：
//   注册 → 登录 → 绑定(iOS 设备 + 一个模拟 Android 设备) → 下发 → 领取 → 回报 → 查结果
//
// 运行方式见 docs/IOS_IMPLEMENTATION_MANUAL.zh-CN.md §7。
// 需要环境变量 AGENTLITE_E2E_BASE 指向一个可达的服务端（本地或线上），
// 且该服务端证书可被 --cacert 校验（默认用 _local/ios-e2e/tls/server.crt）。

import XCTest
@testable import AgentLiteCore

final class EndToEndTests: XCTestCase {

    private var baseURL: URL!
    private var client: AgentLiteClient!
    private var storeDir: String!
    /// 扮演"另一台设备"时用的会话。必须与客户端同一条 pin 校验路径——
    /// 用 URLSession.shared 会绕过 pin，测试就失去了对生产信任链的覆盖。
    private var pinner: CertificatePinner!
    private var pinnedSession: URLSession!

    override func setUpWithError() throws {
        guard let base = ProcessInfo.processInfo.environment["AGENTLITE_E2E_BASE"],
              let url = URL(string: base) else {
            throw XCTSkip("未设置 AGENTLITE_E2E_BASE，跳过端到端测试（属预期：默认只跑离线单测）")
        }
        baseURL = url
        storeDir = NSTemporaryDirectory() + "agentlite-e2e-\(UUID().uuidString)"
        let store = FileCredentialStore(path: storeDir + "/credentials.json")

        // ★ 刻意**不走** insecure 旁路：本地服务端用自签证书，这里把该证书的 SPKI
        //   指纹喂给正常的 pin 校验路径。于是端到端测试覆盖的正是**生产同一条代码路径**
        //   （CertificatePinner + URLSession delegate），而不是一条只在测试里存在的捷径。
        //   证书路径由 AGENTLITE_E2E_CERT 指定，默认用仓库内 _local/ios-e2e/tls/server.crt。
        guard let certPath = ProcessInfo.processInfo.environment["AGENTLITE_E2E_CERT"],
              let spki = CertificatePinnerSupport.spkiSHA256Base64(certificateFile: certPath) else {
            throw XCTSkip("未设置 AGENTLITE_E2E_CERT（本地服务端证书路径），跳过端到端测试")
        }

        pinner = CertificatePinner(pinnedSPKISHA256: [spki])
        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = 20
        pinnedSession = URLSession(configuration: cfg, delegate: pinner, delegateQueue: nil)

        client = AgentLiteClient(
            config: .init(baseURL: url, pinnedSPKISHA256: [spki]),
            secureStore: store,
            cursorStore: store,
            localActions: EchoActionProvider()
        )
    }

    override func tearDownWithError() throws {
        if let storeDir { try? FileManager.default.removeItem(atPath: storeDir) }
    }

    /// 造一个唯一账号名（服务端要求 3-32 位小写字母/数字/下划线/连字符）。
    private func uniqueAccount() -> String {
        "iose2e\(Int(Date().timeIntervalSince1970))\(Int.random(in: 100...999))"
    }

    // MARK: - 账号

    func testRegisterLoginAndDeviceBinding() async throws {
        let name = uniqueAccount()
        let key = "test-key-\(Int.random(in: 100000...999999))"

        let creds = try await client.register(name: name, key: key)
        XCTAssertEqual(creds.accountName, name.lowercased())
        XCTAssertFalse(creds.accountID.isEmpty, "服务端必须签发 account_id")
        XCTAssertNotNil(creds.deviceID, "注册流程必须完成设备绑定（否则后续设备级接口全 400）")
        XCTAssertTrue(creds.deviceID!.hasPrefix("d_"), "设备 ID 由服务端签发，形如 d_xxx")

        // 重新登录同一账号：设备指纹稳定 → 不应新建第二台设备
        let again = try await client.login(name: name, key: key)
        XCTAssertEqual(again.deviceID, creds.deviceID, "同机重登不应产生幽灵设备")

        let profile = try await client.profile()
        XCTAssertEqual(profile.devices.count, 1, "同机重登后设备数应仍为 1，实际 \(profile.devices.count)")
        XCTAssertEqual(profile.name, name.lowercased())
    }

    func testWrongKeyIsRejected() async throws {
        let name = uniqueAccount()
        _ = try await client.register(name: name, key: "right-key-12345678")
        do {
            _ = try await client.login(name: name, key: "wrong-key-12345678")
            XCTFail("错误密钥必须被拒")
        } catch let e as AgentLiteError {
            if case .server(let se) = e {
                XCTAssertEqual(se.status, 401)
                // 服务端统一文案，不区分账号不存在/密钥错（防账号枚举）
                XCTAssertEqual(se.code, "bad_credentials")
            } else {
                XCTFail("预期服务端错误，实际 \(e)")
            }
        }
    }

    // MARK: - 远程控制全链路（本端 = 目标设备）

    /// 这是 iOS 端「远程控制」的核心验收：能在 Mac 上跑通，就说明协议层是对的；
    /// 真机只是换了运行环境。
    func testRemoteCommandRoundTrip() async throws {
        let name = uniqueAccount()
        _ = try await client.register(name: name, key: "e2e-key-12345678")

        // 目标设备 = 本端（Swift）。下发方另造一台"Android 设备"，
        // 用原始 HTTP 直接调服务端扮演它，避免测试里为了造一台设备而复制整个客户端。
        let token = await client.http.currentToken!
        let myDevice = await client.http.currentDeviceID!
        let senderDevice = try await bindFakeAndroidDevice(token: token)

        // 1) Android 侧下发指令
        let sent = try await createCommandFrom(token: token, device: senderDevice, to: myDevice, task: "[跨设备指令] 打开设置")
        XCTAssertEqual(sent.status, "pending")
        XCTAssertEqual(sent.fromDevice, senderDevice)
        XCTAssertEqual(sent.toDevice, myDevice)

        // 2) iOS 侧领取
        let pending = try await client.commands.pending()
        XCTAssertEqual(pending.count, 1, "目标设备应领到 1 条指令")
        XCTAssertEqual(pending.first?.commandID, sent.commandID)

        // 3) iOS 侧执行并回报（EchoActionProvider 会回 done）
        let report = try await client.collectRemoteTasks()
        XCTAssertEqual(report.fetched, 1)
        XCTAssertEqual(report.executed, 1, "详情: \(report.details)")
        XCTAssertEqual(report.failed, 0)

        // 4) 结果就在 ack 的返回值里
        XCTAssertEqual(report.acked.count, 1)
        XCTAssertEqual(report.acked.first?.status, "done")
        XCTAssertEqual(report.acked.first?.detail, "回显: 打开设置", "回报内容应带执行结果，便于下发方判断")

        // 5) ★ 服务端事实：终态 ack 之后指令会被删除，再查就是 404（command.go:195）。
        //    这里显式断言它，免得后来者以为「查询失败」是 bug 而去乱改。
        do {
            _ = try await getCommandFrom(token: token, id: sent.commandID)
            XCTFail("终态 ack 后服务端应已删除该指令，查询应为 404")
        } catch let e as AgentLiteError {
            if case .transport(let m) = e {
                XCTAssertTrue(m.contains("404"), "预期 404，实际: \(m)")
            } else {
                XCTFail("预期 404 transport 错误，实际 \(e)")
            }
        }
    }

    /// 自己给自己下指令必须被服务端拒（ErrCommandSelf）。
    func testCannotCommandSelf() async throws {
        _ = try await client.register(name: uniqueAccount(), key: "e2e-key-12345678")
        let myDevice = await client.http.currentDeviceID!
        do {
            _ = try await client.commands.send(toDeviceID: myDevice, task: "打开设置")
            XCTFail("服务端应拒绝给自己下指令")
        } catch let e as AgentLiteError {
            if case .server(let se) = e {
                XCTAssertEqual(se.code, "command_self")
            } else {
                XCTFail("预期服务端错误，实际 \(e)")
            }
        }
    }

    /// 敏感任务：即使被领取，也必须以 failed 终态回报（带拒绝理由），且不进入执行层。
    func testSensitiveTaskIsRejectedWithCancelledStatus() async throws {
        _ = try await client.register(name: uniqueAccount(), key: "e2e-key-12345678")
        let token = await client.http.currentToken!
        let myDevice = await client.http.currentDeviceID!
        let sender = try await bindFakeAndroidDevice(token: token)

        let sent = try await createCommandFrom(token: token, device: sender, to: myDevice, task: "[跨设备指令] 帮我转账 500 元")
        let report = try await client.collectRemoteTasks()
        XCTAssertEqual(report.fetched, 1)
        XCTAssertEqual(report.acked.count, 1, "必须回报终态，否则下发方永远看不到结果：\(report.summary)")

        let final = try XCTUnwrap(report.acked.first)
        XCTAssertEqual(final.commandID, sent.commandID)
        XCTAssertEqual(final.status, "failed", "敏感操作必须以 failed 回报，不能是 done")
        XCTAssertTrue(final.detail?.contains("敏感操作") == true, "详情: \(final.detail ?? "nil")")
    }

    /// iOS 无系统级自动化能力：能力之外的任务应如实回报 failed（而不是假装成功）。
    func testUnsupportedTaskReportsFailed() async throws {
        _ = try await client.register(name: uniqueAccount(), key: "e2e-key-12345678")
        let token = await client.http.currentToken!
        let myDevice = await client.http.currentDeviceID!
        let sender = try await bindFakeAndroidDevice(token: token)

        let sent = try await createCommandFrom(token: token, device: sender, to: myDevice, task: "[跨设备指令] 打开微信给妈妈发消息")
        let report = try await client.collectRemoteTasks()

        let final = try XCTUnwrap(report.acked.first)
        XCTAssertEqual(final.commandID, sent.commandID)
        XCTAssertEqual(final.status, "failed")
        XCTAssertTrue(final.detail?.contains("无法执行") == true, "要说清为什么做不到，详情: \(final.detail ?? "nil")")
    }

    /// iOS 作为下发方：能列设备、能下发、能跟踪结果。
    func testIOSCanSendToPeerDevice() async throws {
        _ = try await client.register(name: uniqueAccount(), key: "e2e-key-12345678")
        let token = await client.http.currentToken!
        let peer = try await bindFakeAndroidDevice(token: token)

        let peers = try await client.peerDevices()
        XCTAssertEqual(peers.count, 1)
        XCTAssertEqual(peers.first?.deviceID, peer, "设备列表不应包含本机（服务端拒绝给自己下指令）")

        let sent = try await client.sendRemoteTask(toDeviceID: peer, task: "打开 B 站")
        XCTAssertEqual(sent.status, "pending")
        XCTAssertTrue(sent.task.hasPrefix("[跨设备指令] "), "任务文本必须带前缀，让目标端知道这不是本机用户当面下达")
        XCTAssertEqual(sent.task, "[跨设备指令] 打开 B 站")
    }

    /// 指令回报只能由目标设备发起（防伪造结果）。
    func testOnlyTargetDeviceCanAck() async throws {
        _ = try await client.register(name: uniqueAccount(), key: "e2e-key-12345678")
        let token = await client.http.currentToken!
        let myDevice = await client.http.currentDeviceID!
        let sender = try await bindFakeAndroidDevice(token: token)

        let sent = try await createCommandFrom(token: token, device: sender, to: myDevice, task: "[跨设备指令] 打开设置")

        // 下发方（非目标设备）尝试 ack → 必须 403
        do {
            _ = try await ackFrom(token: token, device: sender, commandID: sent.commandID, status: "done", detail: "伪造")
            XCTFail("非目标设备不得回报结果")
        } catch let e as AgentLiteError {
            if case .server(let se) = e {
                XCTAssertEqual(se.code, "not_target", "预期 not_target，实际 \(se.code)")
            } else {
                XCTFail("预期服务端错误，实际 \(e)")
            }
        }
    }

    // MARK: - 同步流

    func testSyncPushPullRoundTrip() async throws {
        _ = try await client.register(name: uniqueAccount(), key: "e2e-key-12345678")
        let cursor0 = try await client.sync.cursor()
        XCTAssertEqual(cursor0, 0, "新账号游标应从 0 开始")

        let item = SyncOutgoingItem(
            id: "task-\(UUID().uuidString.prefix(8))",
            ts: ISO8601DateFormatter().string(from: Date()),
            data: .object(["kind": .string("demo"), "n": .number(42)])
        )
        let pushed = try await client.sync.push(tasks: [item])
        XCTAssertEqual(pushed.accepted, 1)
        XCTAssertEqual(pushed.rejected, 0)
        XCTAssertEqual(pushed.duplicates, 0)

        // 幂等性：同一 id 再推一次，服务端按 id 去重、不产生重复记录
        let again = try await client.sync.push(tasks: [item])
        XCTAssertEqual(again.accepted, 0, "重复推送不应被再次接受")
        XCTAssertEqual(again.duplicates, 1, "重复项应计入 duplicates")

        let records = try await client.sync.drain()
        XCTAssertEqual(records.count, 1)
        XCTAssertEqual(records.first?.kind, "task")
        XCTAssertEqual(records.first?.id, item.id)
        // data 必须原样往返 —— 服务端不解释它，iOS 端也不能丢字段
        let obj = records.first?.data.objectValue
        XCTAssertEqual(obj?["kind"]?.stringValue, "demo")
        XCTAssertEqual(obj?["n"], .number(42))

        let cursor1 = try await client.sync.cursor()
        XCTAssertGreaterThan(cursor1, cursor0, "拉取后游标必须前进，否则会重复处理")
    }

    // MARK: - 局域网（跨实现互解）

    /// 局域网密钥派生 + 信封互解：用 Swift 造一个「对端」，验证
    /// 「AAD 只绑 account_id、device_id 在明文内」这套约定在 Swift 侧同样成立。
    func testLANEnvelopeCompatibleWithProtocol() async throws {
        let creds = try await client.register(name: uniqueAccount(), key: "lan-key-12345678")
        let accountKey = creds.accountKey
        let accountID = creds.accountID

        let swift = LANPeer(accountKey: accountKey, accountID: accountID, deviceID: creds.deviceID!)
        // 扮演 Android 对端：同账号密钥、不同 device_id
        let androidish = LANPeer(accountKey: accountKey, accountID: accountID, deviceID: "d_androidsim")

        let plain = try await swift.encodeOutbound(since: 3, tasks: [], memories: [])
        let envelope = try AgentLiteCrypto.seal(
            key: AgentLiteCrypto.deriveLANKey(accountKey: accountKey, accountID: accountID),
            aad: "agentlite-lan-v1|\(accountID)",
            plaintext: plain
        )
        let (from, payload) = try await androidish.acceptInbound(envelope: envelope)
        XCTAssertEqual(from, creds.deviceID)
        XCTAssertEqual(payload.since, 3)

        // 回包也要能解开
        let resp = try await androidish.respondInbound(serverSeq: 9, records: [])
        let back = try await swift.decodeInbound(
            AgentLiteCrypto.open(
                key: AgentLiteCrypto.deriveLANKey(accountKey: accountKey, accountID: accountID),
                aad: "agentlite-lan-v1|\(accountID)",
                envelope: resp
            )
        )
        XCTAssertEqual(back.serverSeq, 9)
    }

    // MARK: - 扮演「另一台设备」的原始 HTTP 调用

    /// 直接打服务端 API，省得为了造一台假设备复制整个客户端。
    private func rawRequest(token: String, method: String, path: String, body: [String: Any]?, device: String?) async throws -> [String: Any] {
        var req = URLRequest(url: baseURL.appendingPathComponent(path))
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let device { req.setValue(device, forHTTPHeaderField: "X-Device-Id") }
        if let body { req.httpBody = try JSONSerialization.data(withJSONObject: body) }

        let (data, resp) = try await pinnedSession.data(for: req)
        guard let http = resp as? HTTPURLResponse else { throw AgentLiteError.transport("非 HTTP 响应") }
        let obj = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
        guard (200..<300).contains(http.statusCode) else {
            throw AgentLiteError.server(ServerError(
                status: http.statusCode,
                code: obj["error"] as? String ?? "unknown",
                message: obj["message"] as? String ?? ""
            ))
        }
        return obj
    }

    @discardableResult
    private func bindFakeAndroidDevice(token: String) async throws -> String {
        let res = try await rawRequest(
            token: token, method: "POST", path: "/api/v1/device/bind",
            body: [
                "fingerprint": "android-sim-\(UUID().uuidString)",
                "name": "AndroidSim",
                "model": "PKX110",
                "brand": "OnePlus",
                "sdk": 36,
                "app_version": "e2e",
            ],
            device: nil
        )
        guard let id = res["device_id"] as? String else {
            throw AgentLiteError.decoding("绑定假设备失败: \(res)")
        }
        return id
    }

    private func createCommandFrom(token: String, device: String, to: String, task: String) async throws -> SyncedCommand {
        let res = try await rawRequest(
            token: token, method: "POST", path: "/api/v1/device/command",
            body: ["to_device": to, "task": task],
            device: device
        )
        let data = try JSONSerialization.data(withJSONObject: res)
        return try JSONDecoder().decode(CommandEnvelope.self, from: data).command
    }

    private func getCommandFrom(token: String, id: String) async throws -> SyncedCommand {
        var comps = URLComponents(url: baseURL.appendingPathComponent("/api/v1/device/command"), resolvingAgainstBaseURL: false)!
        comps.queryItems = [URLQueryItem(name: "id", value: id)]
        var req = URLRequest(url: comps.url!)
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        let (data, resp) = try await pinnedSession.data(for: req)
        let http = resp as! HTTPURLResponse
        guard (200..<300).contains(http.statusCode) else {
            throw AgentLiteError.transport("查指令失败: \(http.statusCode)")
        }
        return try JSONDecoder().decode(CommandEnvelope.self, from: data).command
    }

    private func ackFrom(token: String, device: String, commandID: String, status: String, detail: String) async throws -> SyncedCommand {
        let res = try await rawRequest(
            token: token, method: "POST", path: "/api/v1/device/command/ack",
            body: ["command_id": commandID, "status": status, "detail": detail],
            device: device
        )
        let data = try JSONSerialization.data(withJSONObject: res)
        return try JSONDecoder().decode(CommandEnvelope.self, from: data).command
    }
}

/// 测试用 provider：把任务回显成执行结果。
/// 用它而不是 NoLocalActionsProvider，是为了验证「provider 说能做 → 回报 done」这条正路。
struct EchoActionProvider: LocalActionProvider {
    var capabilities: [String] { ["回显"] }

    func perform(task: String) async throws -> ExecutionOutcome? {
        // 只处理明确"能做"的任务；其余交给执行器的能力边界分支
        let supported = ["打开设置", "打开相机", "记一条笔记"]
        guard supported.contains(where: { task.contains($0) }) else { return nil }
        return .done("回显: \(task)")
    }
}
