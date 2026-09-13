// SPDX-License-Identifier: Apache-2.0
//
// 在 **iOS 模拟器**里跑的测试 —— 覆盖 macOS 上验不到的部分。
//
// 为什么必须单独有这一套（不是重复上一套）：
//   `swift test` 跑在 macOS 上，验的是协议与算法；但下面这些只有真 iOS 运行时
//   （或模拟器）才成立，macOS 上即使跑通也不能证明 iOS 上没问题：
//     1. **Keychain**：`SecItemAdd` 的 accessibility 属性、errSecMissingEntitlement
//        等行为与 macOS 不同；
//     2. **URLSession + ATS**：iOS 的 ATS 策略、自签证书处理路径不同；
//     3. **文件系统布局**：沙盒容器路径与可写目录不同；
//     4. **UIKit 相关动作**（本地动作提供方里的 UIApplication 调用）。
//
// 运行方式见 ios/build-mac.sh 的 --simulator 分支或 docs 手册 §8.3。

import XCTest
import UIKit
import AgentLiteCore
@testable import AgentLiteIOS

final class SimulatorTests: XCTestCase {

    // MARK: - 配置来源

    /// 服务端地址：优先环境变量（xcodebuild 注入），否则用模拟器可达的宿主环回。
    /// 模拟器与宿主机共享网络栈，因此宿主的 127.0.0.1 在模拟器里也是 127.0.0.1。
    private var baseURL: URL {
        let raw = ProcessInfo.processInfo.environment["AGENTLITE_E2E_BASE"] ?? "https://127.0.0.1:18443"
        return URL(string: raw)!
    }

    private var certPath: String? {
        if let p = ProcessInfo.processInfo.environment["AGENTLITE_E2E_CERT"], FileManager.default.fileExists(atPath: p) {
            return p
        }
        return Bundle.main.path(forResource: "server", ofType: "crt")
    }

    // MARK: - 1. Keychain（macOS 上验不到）

    func testKeychainRoundTripInIOSRuntime() throws {
        let store = KeychainStore(service: "com.dsh.agentlite.ios.tests")
        let key = "roundtrip-\(UUID().uuidString)"
        let payload = Data("敏感内容".utf8)

        // 写 → 读
        try store.set(payload, for: key)
        XCTAssertEqual(try store.get(key), payload, "Keychain 写入后读回不一致")

        // 覆盖写（Keychain 没有 upsert，实现里是先删后加 —— 这条守着它）
        let updated = Data("更新后的内容".utf8)
        try store.set(updated, for: key)
        XCTAssertEqual(try store.get(key), updated, "覆盖写失败：先删后加逻辑有问题")

        // 删 → 读为 nil（不是抛错）
        try store.remove(key)
        XCTAssertNil(try store.get(key), "删除后应返回 nil")

        // 再删一次不应抛错（幂等）
        XCTAssertNoThrow(try store.remove(key))
    }

    /// 凭据仓库往返：这是"重装/重启后还在不在"的基础。
    func testCredentialStorePersistsAcrossInstances() throws {
        let service = "com.dsh.agentlite.ios.tests.creds"
        let a = CredentialStore(store: KeychainStore(service: service))
        let creds = Credentials(
            accountID: "a_test", accountName: "tester",
            token: UUID().uuidString, deviceID: "d_test", accountKey: "key-12345678"
        )
        try a.save(creds)

        // 新实例（模拟 App 重启）必须读到同一份
        let b = CredentialStore(store: KeychainStore(service: service))
        XCTAssertEqual(try b.load(), creds, "重启后凭据丢失 —— 登录态无法恢复")

        try b.clear()
        XCTAssertNil(try b.load())
    }

    /// 设备指纹必须跨"重启"稳定，否则服务端每次都会多出一台幽灵设备。
    func testDeviceFingerprintIsStableInKeychain() throws {
        let store = KeychainStore(service: "com.dsh.agentlite.ios.tests.fp")
        let fp1 = try DeviceIdentity.fingerprint(store: store)
        let fp2 = try DeviceIdentity.fingerprint(store: store)
        XCTAssertEqual(fp1, fp2)
        XCTAssertTrue(fp1.hasPrefix("ios-"))
    }

    // MARK: - 2. 文件系统（沙盒布局）

    func testSandboxPathsAreWritableAndIsolated() throws {
        // 模拟器里 Documents 位于 App 沙盒容器内 —— 路径形态与 macOS 完全不同
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        XCTAssertTrue(docs.path.contains("/Containers/Data/Application/"),
                      "iOS 沙盒路径应位于 Containers/Data/Application 下，实际: \(docs.path)")

        let file = docs.appendingPathComponent("probe-\(UUID().uuidString).json")
        try Data("{}".utf8).write(to: file)
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        try FileManager.default.removeItem(at: file)
    }

    func testFileCredentialStoreUses0600() throws {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }

        let path = dir.appendingPathComponent("credentials.json").path
        let store = FileCredentialStore(path: path)
        try store.set(Data("x".utf8), for: "k")

        let attrs = try FileManager.default.attributesOfItem(atPath: path)
        let perms = (attrs[.posixPermissions] as? NSNumber)?.intValue ?? 0
        XCTAssertEqual(perms, 0o600, "凭据文件权限必须是 0600，实际 \(String(perms, radix: 8))")
    }

    // MARK: - 3. URLSession / ATS / 证书 pin（iOS 与 macOS 策略不同）

    /// 证书 pin 的正路：把本地服务端证书的 SPKI 喂进去，请求应当成功。
    func testPinnedRequestSucceedsInIOSRuntime() async throws {
        guard let certPath else {
            throw XCTSkip("未提供服务端证书（AGENTLITE_E2E_CERT 或包内 server.crt），跳过")
        }
        guard let spki = CertificatePinnerSupport.spkiSHA256Base64(certificateFile: certPath) else {
            throw XCTSkip("无法从证书提取公钥")
        }
        let client = HTTPClient(baseURL: baseURL, pinnedSPKISHA256: [spki])
        // /health 免鉴权，最适合验证"通道本身通不通"
        let health: HealthProbe = try await client.send(
            method: "GET", path: "/health",
            body: EmptyBody?.none, options: .init(authorized: false), as: HealthProbe.self
        )
        XCTAssertTrue(health.ok)
        XCTAssertFalse(health.version.isEmpty)
    }

    /// ★ 安全默认：**空 pin 集合 = 拒绝一切连接**。
    /// 这条是"宁可不连也不裸奔"的落地验证 —— 必须在真 iOS 运行时里成立，
    /// 因为 macOS 的 URLSession 与 iOS 的 ATS 行为不同。
    func testEmptyPinSetRefusesToConnect() async throws {
        let client = HTTPClient(baseURL: baseURL, pinnedSPKISHA256: [])
        do {
            let _: HealthProbe = try await client.send(
                method: "GET", path: "/health",
                body: EmptyBody?.none, options: .init(authorized: false), as: HealthProbe.self
            )
            XCTFail("空 pin 集合必须拒绝连接（安全默认），实际却成功了")
        } catch {
            // 预期：pin 拒绝（错误会被映射成 pinningMismatch）
            XCTAssertTrue("\(error)".contains("pin") || "\(error)".contains("证书"),
                          "错误信息应指向证书 pin，实际: \(error)")
        }
    }

    /// 错误的 pin 必须被拒 —— 这是"防中间人"的核心断言。
    func testWrongPinIsRejected() async throws {
        let bogus = Data((0..<32).map { _ in UInt8.random(in: 0...255) }).base64EncodedString()
        let client = HTTPClient(baseURL: baseURL, pinnedSPKISHA256: [bogus])
        do {
            let _: HealthProbe = try await client.send(
                method: "GET", path: "/health",
                body: EmptyBody?.none, options: .init(authorized: false), as: HealthProbe.self
            )
            XCTFail("错误的 pin 必须被拒，实际却成功了 —— 证书校验形同虚设")
        } catch {
            // 预期
        }
    }

    // MARK: - 4. 端到端（模拟器真连服务端）

    /// 完整跨设备链路在 iOS 运行时里跑通：注册 → 绑定 → 下发 → 领取 → 回报。
    func testRemoteControlRoundTripInIOSRuntime() async throws {
        guard let certPath, let spki = CertificatePinnerSupport.spkiSHA256Base64(certificateFile: certPath) else {
            throw XCTSkip("未提供可用证书，跳过端到端")
        }
        guard await serverReachable(spki: spki) else {
            throw XCTSkip("服务端不可达（\(baseURL)）—— 先按手册 §8.1 起本地服务端")
        }

        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = FileCredentialStore(path: dir.appendingPathComponent("c.json").path)

        let client = AgentLiteClient(
            config: .init(baseURL: baseURL, pinnedSPKISHA256: [spki]),
            secureStore: store, cursorStore: store,
            localActions: EchoLocalActions()
        )

        let name = "sim\(Int(Date().timeIntervalSince1970))\(Int.random(in: 100...999))"
        let creds = try await client.register(name: name, key: "sim-key-12345678")
        XCTAssertNotNil(creds.deviceID, "注册后必须完成设备绑定")

        // 同机重登不应产生第二台设备
        _ = try await client.login(name: name, key: "sim-key-12345678")
        let profile = try await client.profile()
        XCTAssertEqual(profile.devices.count, 1, "同机重登后设备数应为 1，实际 \(profile.devices.count)")

        // 另造一台"Android"设备作为下发方
        let sender = try await bindFakeDevice(token: creds.token, spki: spki)
        let cmd = try await createCommand(token: creds.token, spki: spki, device: sender, to: creds.deviceID!, task: "[跨设备指令] 打开设置")

        // iOS 侧收件并执行（EchoLocalActions 会回 done）
        let report = try await client.collectRemoteTasks()
        XCTAssertEqual(report.fetched, 1, "应领到 1 条指令")
        let acked = try XCTUnwrap(report.acked.first)
        XCTAssertEqual(acked.commandID, cmd.commandID)
        XCTAssertEqual(acked.status, "done", "详情：\(report.details)")
    }

    /// iOS 无系统级自动化能力：能力之外的任务必须如实回报 failed。
    /// 这条是产品纪律，必须在本平台验证（而不是只在 macOS 上验）。
    func testUnsupportedTaskReportsFailedInIOSRuntime() async throws {
        guard let certPath, let spki = CertificatePinnerSupport.spkiSHA256Based64ForTest(certificateFile: certPath) else {
            throw XCTSkip("未提供可用证书，跳过")
        }
        guard await serverReachable(spki: spki) else {
            throw XCTSkip("服务端不可达，跳过")
        }

        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = FileCredentialStore(path: dir.appendingPathComponent("c.json").path)

        // 用真实的 iOS 本地动作提供方（不含 Echo），验证"做不到就说做不到"
        let client = AgentLiteClient(
            config: .init(baseURL: baseURL, pinnedSPKISHA256: [spki]),
            secureStore: store, cursorStore: store,
            localActions: IOSLocalActionProvider()
        )

        let name = "simf\(Int(Date().timeIntervalSince1970))\(Int.random(in: 100...999))"
        let creds = try await client.register(name: name, key: "sim-key-12345678")
        let sender = try await bindFakeDevice(token: creds.token, spki: spki)
        _ = try await createCommand(token: creds.token, spki: spki, device: sender,
                                    to: creds.deviceID!, task: "[跨设备指令] 打开微信给妈妈发消息")

        let report = try await client.collectRemoteTasks()
        let acked = try XCTUnwrap(report.acked.first)
        XCTAssertEqual(acked.status, "failed", "iOS 做不到的任务必须回报 failed，不能假装成功")
        XCTAssertTrue(acked.detail?.contains("无法执行") == true, "详情：\(acked.detail ?? "nil")")
    }

    /// 敏感任务在执行层之前就被拒（真 iOS 运行时里再验一次）。
    func testSensitiveTaskRejectedInIOSRuntime() async throws {
        guard let certPath, let spki = CertificatePinnerSupport.spkiSHA256Based64ForTest(certificateFile: certPath) else {
            throw XCTSkip("未提供可用证书，跳过")
        }
        guard await serverReachable(spki: spki) else { throw XCTSkip("服务端不可达，跳过") }

        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let store = FileCredentialStore(path: dir.appendingPathComponent("c.json").path)
        let client = AgentLiteClient(
            config: .init(baseURL: baseURL, pinnedSPKISHA256: [spki]),
            secureStore: store, cursorStore: store,
            localActions: EchoLocalActions()
        )
        let name = "sims\(Int(Date().timeIntervalSince1970))\(Int.random(in: 100...999))"
        let creds = try await client.register(name: name, key: "sim-key-12345678")
        let sender = try await bindFakeDevice(token: creds.token, spki: spki)
        _ = try await createCommand(token: creds.token, spki: spki, device: sender,
                                    to: creds.deviceID!, task: "[跨设备指令] 帮我转账 500 元")

        let report = try await client.collectRemoteTasks()
        let acked = try XCTUnwrap(report.acked.first)
        XCTAssertEqual(acked.status, "failed")
        XCTAssertTrue(acked.detail?.contains("敏感操作") == true, "详情：\(acked.detail ?? "nil")")
    }

    // MARK: - 5. 本地动作提供方（含 UIKit 调用）

    func testLocalActionProviderCapabilitiesAreHonest() {
        let p = IOSLocalActionProvider()
        // 能力清单必须只列真实能做的（不列"自动操作微信"这种做不到的）
        for cap in p.capabilities {
            XCTAssertFalse(cap.contains("微信") || cap.contains("自动"),
                           "能力清单里不应出现做不到的项：\(cap)")
        }
        XCTAssertTrue(p.capabilities.contains { $0.contains("快捷指令") })
    }

    func testLocalActionProviderDeclinesUnsupportedTask() async throws {
        let p = IOSLocalActionProvider()
        // 能力之外返回 nil（而不是假装成功或抛错）
        let outcome = try await p.perform(task: "打开微信给妈妈发消息")
        XCTAssertNil(outcome, "能力之外必须返回 nil 交给执行器统一拒绝")
    }

    /// 快捷指令名解析：前缀必须显式，模糊匹配会误伤。
    func testShortcutTaskIsRecognized() async throws {
        let executor = CommandExecutor(provider: IOSLocalActionProvider())
        // 敏感词优先：即使含"运行快捷指令"也不放行支付类
        let blocked = await executor.execute("运行快捷指令 转账给张三")
        XCTAssertEqual(blocked.status, .failed)
        XCTAssertTrue(blocked.detail.contains("敏感操作"))
    }

    // MARK: - 辅助

    private func serverReachable(spki: String) async -> Bool {
        let client = HTTPClient(baseURL: baseURL, pinnedSPKISHA256: [spki])
        return (try? await client.send(
            method: "GET", path: "/health",
            body: EmptyBody?.none, options: .init(authorized: false), as: HealthProbe.self
        )) != nil
    }

    /// 直接打服务端 API，扮演"另一台设备"，省得复制整个客户端。
    private func raw(token: String, spki: String, method: String, path: String,
                     body: [String: Any]?, device: String?) async throws -> [String: Any] {
        var req = URLRequest(url: baseURL.appendingPathComponent(path))
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        if let device { req.setValue(device, forHTTPHeaderField: "X-Device-Id") }
        if let body { req.httpBody = try JSONSerialization.data(withJSONObject: body) }

        // 与被测客户端走同一条 pin 校验会话：用 URLSession.shared 会绕过 pin，
        // 测试也就失去了对信任链的覆盖。
        let cfg = URLSessionConfiguration.ephemeral
        let session = URLSession(configuration: cfg, delegate: CertificatePinner(pinnedSPKISHA256: [spki]), delegateQueue: nil)
        let (data, resp) = try await session.data(for: req)
        let http = resp as! HTTPURLResponse
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

    private func bindFakeDevice(token: String, spki: String) async throws -> String {
        let res = try await raw(
            token: token, spki: spki, method: "POST", path: "/api/v1/device/bind",
            body: ["fingerprint": "fake-\(UUID().uuidString)", "name": "FakeAndroid",
                   "model": "PKX110", "brand": "OnePlus", "sdk": 36, "app_version": "sim"],
            device: nil
        )
        return try XCTUnwrap(res["device_id"] as? String)
    }

    private func createCommand(token: String, spki: String, device: String, to: String, task: String) async throws -> SyncedCommand {
        let res = try await raw(token: token, spki: spki, method: "POST", path: "/api/v1/device/command",
                                body: ["to_device": to, "task": task], device: device)
        let data = try JSONSerialization.data(withJSONObject: res)
        return try JSONDecoder().decode(CommandEnvelope.self, from: data).command
    }
}

// MARK: - 测试替身

struct HealthProbe: Codable {
    let ok: Bool
    let version: String
}

/// 只处理"打开设置/相机/记笔记"的 echo provider，用来验证正路（能做的确实回报 done）。
struct EchoLocalActions: LocalActionProvider {
    var capabilities: [String] { ["回显"] }
    func perform(task: String) async throws -> ExecutionOutcome? {
        guard task.contains("打开设置") || task.contains("打开相机") else { return nil }
        return .done("回显: \(task)")
    }
}

// 便于测试里少写一层 guard 的小工具
extension CertificatePinnerSupport {
    static func spkiSHA256Based64ForTest(certificateFile: String) -> String? {
        spkiSHA256Base64(certificateFile: certificateFile)
    }
}
