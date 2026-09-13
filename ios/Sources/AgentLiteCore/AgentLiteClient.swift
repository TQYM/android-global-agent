// SPDX-License-Identifier: Apache-2.0
//
// 顶层门面：把 HTTP / 账号 / 同步 / 指令 / 局域网 组装成一个 iOS App 能直接用的对象。
//
// 分层的意义在这里兑现：`AgentLiteClient` 不导入 UIKit，所以
//   - iOS App 里它由 SwiftUI 持有；
//   - macOS 上 `swift test` 能把它整体跑起来（真连服务器做端到端验证，见手册 §7）。

import Foundation

public actor AgentLiteClient {

    public struct Config: Sendable {
        public var baseURL: URL
        /// 服务端证书 SPKI SHA-256（base64）集合。轮换期可放多个。
        public var pinnedSPKISHA256: Set<String>
        /// 允许无 pin 的环回地址（仅本地联调 / 端到端测试）。
        public var allowInsecureLocalhost: Bool

        public init(baseURL: URL, pinnedSPKISHA256: Set<String>, allowInsecureLocalhost: Bool = false) {
            self.baseURL = baseURL
            self.pinnedSPKISHA256 = pinnedSPKISHA256
            self.allowInsecureLocalhost = allowInsecureLocalhost
        }

        /// 生产配置占位：把 pin 换成 agentlite-server/tls/server.crt 的 SPKI 指纹。
        /// 工具：`swift run agentlite-ios pin --cert <path>`（见 CLI）。
        public static func production(pinnedSPKISHA256: Set<String>) -> Config {
            Config(
                baseURL: URL(string: "https://8.133.225.105:18443")!,
                pinnedSPKISHA256: pinnedSPKISHA256
            )
        }

        public static func localhost(port: Int = 18443) -> Config {
            Config(
                baseURL: URL(string: "https://127.0.0.1:\(port)")!,
                pinnedSPKISHA256: [],
                allowInsecureLocalhost: true
            )
        }
    }

    public let http: HTTPClient
    public let account: AccountService
    public let sync: SyncService
    public let commands: CommandService
    public let inbox: CommandInbox

    private let secureStore: SecureStore

    public init(
        config: Config,
        secureStore: SecureStore = KeychainStore(),
        cursorStore: SecureStore? = nil,
        localActions: LocalActionProvider = NoLocalActionsProvider()
    ) {
        let client = config.allowInsecureLocalhost && config.pinnedSPKISHA256.isEmpty
            ? HTTPClient.insecureLocalhost(baseURL: config.baseURL)
            : HTTPClient(baseURL: config.baseURL, pinnedSPKISHA256: config.pinnedSPKISHA256)

        self.secureStore = secureStore
        self.http = client
        let creds = CredentialStore(store: secureStore)
        self.account = AccountService(http: client, credentials: creds, secureStore: secureStore)
        self.sync = SyncService(http: client, cursorStore: cursorStore ?? secureStore)
        self.commands = CommandService(http: client)
        self.inbox = CommandInbox(commands: self.commands, executor: CommandExecutor(provider: localActions))
    }

    // MARK: - 生命周期

    /// App 启动：恢复登录态。
    @discardableResult
    public func bootstrap() async throws -> Credentials? {
        try await account.restore()
    }

    public func login(name: String, key: String) async throws -> Credentials {
        let creds = try await account.login(name: name, key: key)
        try await afterAuth(creds)
        return creds
    }

    public func register(name: String, key: String) async throws -> Credentials {
        let creds = try await account.register(name: name, key: key)
        try await afterAuth(creds)
        return creds
    }

    private func afterAuth(_ creds: Credentials) async throws {
        // 局域网密钥由账号密钥派生，只在内存/Keychain 中流转，服务端不经手。
        _ = LANPeerFactory.shared
        LANPeerFactory.register(creds: creds)
    }

    public func logout() async throws {
        try await account.logout()
        LANPeerFactory.register(creds: nil)
    }

    // MARK: - 远程控制

    /// iPhone → 其它设备：下发任务。
    public func sendRemoteTask(toDeviceID: String, task: String) async throws -> SyncedCommand {
        try await commands.send(toDeviceID: toDeviceID, task: RemoteTaskPrefix.marker + task)
    }

    /// 本机作为目标设备：收一次件（供前台手动刷新与 iOS 后台唤醒调用）。
    @discardableResult
    public func collectRemoteTasks(budget: TimeInterval = CommandInbox.defaultBudget) async throws -> CommandInbox.DrainReport {
        try await inbox.drainOnce(budget: budget)
    }

    public func peerDevices() async throws -> [ServerDevice] {
        try await account.peerDevices()
    }

    public func profile() async throws -> AccountProfile {
        try await account.profile()
    }

    /// 下发方查结果。
    public func commandResult(commandID: String) async throws -> SyncedCommand {
        try await commands.result(commandID: commandID)
    }

    /// 下发方等待一条指令到终态。有超时上限：指令本身 5 分钟即过期，
    /// 无限等待只会在 404 上打转。
    public func waitForCommand(
        commandID: String,
        timeout: TimeInterval = 300,
        onTick: (@Sendable (SyncedCommand) -> Void)? = nil
    ) async throws -> SyncedCommand {
        try await commands.waitForResult(commandID: commandID, timeout: timeout, onTick: onTick)
    }

    /// 局域网对端同步（iOS 只能做发起方，见 LANPeer 文件头）。
    public func syncWithLANPeer(host: String, port: Int = LANPeer.defaultPort) async throws -> LANPeer.InboundPayload {
        guard let creds = try await account.restore(), let deviceID = creds.deviceID else {
            throw AgentLiteError.notBound
        }
        let peer = LANPeer(accountKey: creds.accountKey, accountID: creds.accountID, deviceID: deviceID)
        let since = try await sync.cursor()
        return try await peer.syncWithPeer(host: host, port: port, since: since)
    }
}

/// 局域网对端的凭据缓存。
/// 为什么用一个静态注册表而不是让调用方每次传账号密钥：账号密钥只在登录时可见，
/// 而 P2P 可能被 UI 在任意时刻触发。这里只保存**已登录账号**的凭据副本，
/// 退出登录即清空（见 `logout`）。
enum LANPeerFactory {
    private static let lock = NSLock()
    private static var creds: Credentials?

    static var shared: Void { () }

    static func register(creds: Credentials?) {
        lock.lock(); defer { lock.unlock() }
        self.creds = creds
    }

    static var current: Credentials? {
        lock.lock(); defer { lock.unlock() }
        return creds
    }
}
