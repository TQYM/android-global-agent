// SPDX-License-Identifier: Apache-2.0
//
// 局域网 P2P（与 Android 端**逐字节**互通的那一半）。
//
// 线协议见 agentlite-server/README.md §4：
//   POST http://<peer>:18444/lan/v1/sync
//   body = base64( nonce(12B) || AES-256-GCM(plaintext, key, nonce, aad) )
//   aad  = "agentlite-lan-v1|" + account_id        ← 注意：**不含 device_id**
//
// ★ AAD 里为什么没有 device_id（踩过的坑，LanSync.java:305-308 有原始记录）：
//   解密前拿不到发送方 device_id（它在密文里），若 AAD 绑它就成了鸡生蛋。
//   折中：AAD 只绑 account_id，device_id 在**明文内**校验 —— 明文受 GCM 保护，
//   所以「来源绑定」的强度没有降低，只是校验点从 AAD 移到了明文。
//   iOS 侧必须照做，否则与 Android 端互解失败。
//
// ★ iOS 特有约束（与 Android 的差异，必须显式承认）：
//   Android 端在局域网接口常驻 ServerSocket 监听（LanSync.java:220），
//   iOS **做不到**：App 进后台后 socket 会被挂起/回收，没有「一直监听」这回事。
//   因此 iOS 只能做 **P2P 的发起方**（主动去连 Android 对端），
//   由 Android 侧承担「监听方」角色。这不是妥协实现，而是平台事实。

import Foundation
import CryptoKit
#if canImport(Network)
import Network
#endif

public actor LANPeer {
    public static let defaultPort = 18444
    public static let path = "/lan/v1/sync"

    private let key: SymmetricKey
    private let accountID: String
    private let deviceID: String

    /// 抗重放：已见过的 (device_id, nonce)，5 分钟滚动窗口（协议 §4.3 要求）。
    private var seen: [String: Date] = [:]
    private let seenTTL: TimeInterval = 300
    private let tsWindow: TimeInterval = 120

    public init(accountKey: String, accountID: String, deviceID: String) {
        self.key = AgentLiteCrypto.deriveLANKey(accountKey: accountKey, accountID: accountID)
        self.accountID = accountID
        self.deviceID = deviceID
    }

    /// 本端 AAD：为什么只用 account_id，见文件头 ★ 说明。
    public var localAAD: String { AgentLiteCrypto.aadPrefix + accountID }

    // MARK: - 出站（iOS → Android 对端）

    public struct OutboundPayload: Codable, Sendable {
        public let ts: Int
        public let deviceID: String
        public let since: Int64
        public let push: PushBody

        public init(ts: Int, deviceID: String, since: Int64, push: PushBody) {
            self.ts = ts
            self.deviceID = deviceID
            self.since = since
            self.push = push
        }

        enum CodingKeys: String, CodingKey {
            case ts, since, push
            case deviceID = "device_id"
        }

        public struct PushBody: Codable, Sendable {
            public let tasks: [SyncOutgoingItem]
            public let memories: [SyncOutgoingItem]
            public init(tasks: [SyncOutgoingItem], memories: [SyncOutgoingItem]) {
                self.tasks = tasks
                self.memories = memories
            }
        }
    }

    public struct InboundPayload: Decodable, Sendable {
        public let ts: Int
        public let serverSeq: Int64?
        public let records: [SyncRecord]

        enum CodingKeys: String, CodingKey {
            case ts, records
            case serverSeq = "server_seq"
        }
    }

    /// 向对端发一次同步。返回对端解密后的响应（已校验 AAD + 新鲜度）。
    public func syncWithPeer(
        host: String,
        port: Int = LANPeer.defaultPort,
        since: Int64 = 0,
        tasks: [SyncOutgoingItem] = [],
        memories: [SyncOutgoingItem] = [],
        timeout: TimeInterval = 8
    ) async throws -> InboundPayload {
        let plaintext = try encodeOutbound(since: since, tasks: tasks, memories: memories)
        let envelope = try AgentLiteCrypto.seal(key: key, aad: localAAD, plaintext: plaintext)

        let urlString = "http://\(host):\(port)\(Self.path)"
        guard let url = URL(string: urlString) else { throw AgentLiteError.badURL(urlString) }

        var req = URLRequest(url: url)
        req.httpMethod = "POST"
        req.setValue("text/plain; charset=utf-8", forHTTPHeaderField: "Content-Type")
        req.httpBody = Data(envelope.utf8)
        req.timeoutInterval = timeout

        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = timeout
        // ATS：局域网明文 HTTP 到内网 IP 需要例外。App 侧 Info.plist 配
        // NSAllowsLocalNetworking（只放开本地网络，不放开任意明文）。
        let session = URLSession(configuration: cfg)

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: req)
        } catch {
            throw AgentLiteError.transport("局域网对端 \(host):\(port) 不可达 —— \(error.localizedDescription)")
        }
        guard let http = response as? HTTPURLResponse else {
            throw AgentLiteError.transport("对端返回非 HTTP 响应")
        }
        guard http.statusCode == 200 else {
            throw AgentLiteError.transport("对端返回 \(http.statusCode)")
        }
        guard let body = String(data: data, encoding: .utf8) else {
            throw AgentLiteError.decoding("对端响应不是 UTF-8")
        }

        let plain = try AgentLiteCrypto.open(key: key, aad: localAAD, envelope: body)
        let inbound = try decodeInbound(plain)
        try checkFreshness(deviceID: nil, envelope: body, ts: inbound.ts)
        return inbound
    }

    // MARK: - 入站（供宿主 App / 测试充当「监听方」时使用）

    /// 校验并解开一条入站信封。返回明文里的 device_id 与载荷。
    /// Android 端是监听方时不需要这个；它存在的意义是**让跨端验证可做**：
    /// 用 macOS 跑一份 Swift 监听端，就能证明 iOS 侧的封包 Android 解得开
    /// （tests/verify_lan_p2p.sh 的对端模拟是同一思路）。
    public func acceptInbound(envelope: String) throws -> (deviceID: String, payload: OutboundPayload) {
        let plain = try AgentLiteCrypto.open(key: key, aad: localAAD, envelope: envelope)
        guard let data = plain.data(using: .utf8) else {
            throw AgentLiteError.decoding("明文不是 UTF-8")
        }
        let payload = try JSONDecoder().decode(OutboundPayload.self, from: data)
        try checkFreshness(deviceID: payload.deviceID, envelope: envelope, ts: payload.ts)
        return (payload.deviceID, payload)
    }

    /// 生成一条入站响应信封（监听方回包）。
    public func respondInbound(serverSeq: Int64, records: [SyncRecord]) throws -> String {
        let body = InboundPayloadEncodable(ts: Int(Date().timeIntervalSince1970), serverSeq: serverSeq, records: records)
        let data = try JSONEncoder().encode(body)
        guard let plain = String(data: data, encoding: .utf8) else {
            throw AgentLiteError.decoding("无法编码响应")
        }
        return try AgentLiteCrypto.seal(key: key, aad: localAAD, plaintext: plain)
    }

    private struct InboundPayloadEncodable: Encodable {
        let ts: Int
        let serverSeq: Int64
        let records: [SyncRecord]
        enum CodingKeys: String, CodingKey {
            case ts, records
            case serverSeq = "server_seq"
        }
    }

    // MARK: - 内部

    func encodeOutbound(since: Int64, tasks: [SyncOutgoingItem], memories: [SyncOutgoingItem]) throws -> String {
        let body = OutboundPayload(
            ts: Int(Date().timeIntervalSince1970),
            deviceID: deviceID,
            since: since,
            push: .init(tasks: tasks, memories: memories)
        )
        let data = try JSONEncoder().encode(body)
        guard let s = String(data: data, encoding: .utf8) else {
            throw AgentLiteError.decoding("无法编码出站报文")
        }
        return s
    }

    func decodeInbound(_ plain: String) throws -> InboundPayload {
        guard let data = plain.data(using: .utf8) else {
            throw AgentLiteError.decoding("明文不是 UTF-8")
        }
        do {
            return try JSONDecoder().decode(InboundPayload.self, from: data)
        } catch {
            throw AgentLiteError.decoding("\(error)")
        }
    }

    /// 协议 §4.3 的两条抗重放检查：时间窗 + (device_id, nonce) 去重。
    /// 只做时间窗是不够的 —— 重放旧报文时密文本身完全合法。
    func checkFreshness(deviceID: String?, envelope: String, ts: Int) throws {
        let now = Date()
        let skew = abs(now.timeIntervalSince1970 - Double(ts))
        guard skew <= tsWindow else {
            throw AgentLiteError.server(ServerError(status: 400, code: "stale_envelope", message: "报文时间戳超出 \(Int(tsWindow)) 秒窗口"))
        }
        guard let nonce = AgentLiteCrypto.nonceB64(ofEnvelope: envelope) else {
            throw AgentLiteError.decoding("信封缺少 nonce")
        }
        let dedupKey = "\(deviceID ?? "?" )|\(nonce)"
        // 顺手清理过期项，避免无界增长（同 LanSync.java:191-199）
        if seen.count > 4096 {
            for (k, t) in seen where now.timeIntervalSince(t) > seenTTL { seen.removeValue(forKey: k) }
        }
        if seen[dedupKey] != nil {
            throw AgentLiteError.server(ServerError(status: 409, code: "replay", message: "重复报文（nonce 已见过）"))
        }
        seen[dedupKey] = now
    }
}
