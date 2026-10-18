// SPDX-License-Identifier: Apache-2.0
//
// App 级状态容器（SwiftUI ↔ AgentLiteCore 的桥）。
//
// 为什么需要它：AgentLiteCore 是 actor 化的、纯协议层；SwiftUI 需要的是
// 主线程可观察的状态。这一层只做**翻译**（actor await → @Published），
// 不做判断 —— 所有分支逻辑留在核心库里，那里有测试。

import Foundation
import SwiftUI
import AgentLiteCore

@MainActor
final class AppModel: ObservableObject {

    // MARK: - 可观察状态

    @Published private(set) var phase: Phase = .loading
    @Published private(set) var accountName: String = ""
    @Published private(set) var devices: [ServerDevice] = []
    @Published private(set) var quota: (used: Int, total: Int) = (0, 0)
    @Published var lastMessage: String = ""
    @Published private(set) var isBusy = false
    /// 本机作为目标设备最近一次收取的报告（含每条指令的执行结果）。
    @Published private(set) var lastDrain: CommandInbox.DrainReport?

    enum Phase: Equatable {
        case loading
        case loggedOut
        case ready
        /// 服务端地址未配置 —— 与"未登录"是两回事，必须分开显示，
        /// 否则用户会以为是自己账号的问题。
        case notConfigured(String)
    }

    // MARK: - 核心

    private var client: AgentLiteClient?
    private let configProvider = ServerConfigProvider()

    /// 证书 pin 来源：优先运行时内置证书（随包分发），保证与生产同一路径。
    init() {
        Task { await bootstrap() }
    }

    func bootstrap() async {
        do {
            let config = try configProvider.load()
            let client = AgentLiteClient(config: config, localActions: IOSLocalActionProvider())
            self.client = client
            if let creds = try await client.bootstrap() {
                accountName = creds.accountName
                phase = .ready
                await refreshProfile()
            } else {
                phase = .loggedOut
            }
        } catch {
            phase = .notConfigured("\(error)")
        }
    }

    // MARK: - 账号

    func login(name: String, key: String, registerNew: Bool) async {
        guard let client else { return }
        isBusy = true
        defer { isBusy = false }
        do {
            let creds = registerNew
                ? try await client.register(name: name, key: key)
                : try await client.login(name: name, key: key)
            accountName = creds.accountName
            phase = .ready
            lastMessage = registerNew ? "注册成功，本机已绑定" : "登录成功"
            await refreshProfile()
        } catch {
            lastMessage = "\(error)"
        }
    }

    func logout() async {
        guard let client else { return }
        try? await client.logout()
        phase = .loggedOut
        devices = []
        lastDrain = nil
        lastMessage = "已退出登录"
    }

    // MARK: - 设备与额度

    func refreshProfile() async {
        guard let client else { return }
        do {
            let profile = try await client.profile()
            quota = (profile.quotaUsed, profile.quota)
            let localDeviceID = await client.http.currentDeviceID
            devices = profile.peerDevices(excluding: localDeviceID)
        } catch {
            lastMessage = "刷新失败：\(error)"
        }
    }

    // MARK: - 远程控制

    /// iPhone 下发指令到另一台设备。
    func send(task: String, to deviceID: String) async {
        guard let client else { return }
        isBusy = true
        defer { isBusy = false }
        do {
            let cmd = try await client.sendRemoteTask(toDeviceID: deviceID, task: task)
            lastMessage = "已下发（\(cmd.commandID.prefix(8))）。完成或失败的结果保留 24 小时，可按指令编号查询。"
        } catch {
            lastMessage = "下发失败：\(error)"
        }
    }

    /// iPhone 作为目标设备收取指令（前台手动触发 / 后台唤醒触发）。
    func collect() async {
        guard let client else { return }
        isBusy = true
        defer { isBusy = false }
        do {
            let report = try await client.collectRemoteTasks()
            lastDrain = report
            lastMessage = report.summary
        } catch {
            lastMessage = "收取失败：\(error)"
        }
    }
}

// MARK: - 服务端配置

/// 服务端地址与证书 pin。
///
/// ★ 生产纪律：**pin 不允许为空**。空 pin 在 HTTPClient 里等价于"拒绝一切连接"，
/// 这是有意的安全默认（宁可不连，也不裸奔）。因此这里若拿不到证书就直接报错，
/// 并把原因说清楚，而不是退回"不校验"。
struct ServerConfigProvider {

    enum ConfigError: Error, CustomStringConvertible {
        case missingBaseURL
        case missingCertificate(String)
        case cannotReadCertificate(String)

        var description: String {
            switch self {
            case .missingBaseURL:
                return "未配置服务端地址（Info.plist 的 AgentLiteBaseURL 为空）"
            case .missingCertificate(let path):
                return "包内缺少服务端证书（\(path)）—— 无法做证书 pin，拒绝以不安全方式连接"
            case .cannotReadCertificate(let path):
                return "读不出服务端证书公钥（\(path)）"
            }
        }
    }

    func load() throws -> AgentLiteClient.Config {
        let base = (Bundle.main.object(forInfoDictionaryKey: "AgentLiteBaseURL") as? String)
            ?? ProcessInfo.processInfo.environment["AGENTLITE_BASE"]
            ?? ""
        guard !base.isEmpty, let url = URL(string: base) else { throw ConfigError.missingBaseURL }

        // 证书随包装在 Resources/ 下（构建时从 agentlite-server/tls/server.crt 复制）
        let certName = (Bundle.main.object(forInfoDictionaryKey: "AgentLiteCertName") as? String) ?? "server"
        guard let certPath = Bundle.main.path(forResource: certName, ofType: "crt") else {
            throw ConfigError.missingCertificate("\(certName).crt")
        }
        guard let spki = CertificatePinnerSupport.spkiSHA256Base64(certificateFile: certPath) else {
            throw ConfigError.cannotReadCertificate(certPath)
        }

        // 环回地址允许免 pin（模拟器联调）：环回不经过网络介质，无中间人面。
        // 但**必须**是环回 —— 公网地址一律要求 pin。
        let isLoopback = HTTPClient.isLoopback(url)
        return AgentLiteClient.Config(
            baseURL: url,
            pinnedSPKISHA256: isLoopback ? [] : [spki],
            allowInsecureLocalhost: isLoopback
        )
    }
}
