// SPDX-License-Identifier: Apache-2.0
//
// HTTPS 客户端 + 证书 pin 校验 + 服务端错误信封映射。
//
// 为什么必须做证书 pin（而不是「用系统信任链就行」）：
// 本项目的服务端是**自签证书**（agentlite-server/README.md §5：openssl 自签，
// SAN 写死服务 IP）。自签证书没法被系统信任链验证，于是只剩两条路：
//   (a) 放开 ATS / 信任任意证书  —— 等于把中间人攻击面彻底打开；
//   (b) 把服务端证书公钥随 App 下发，逐次比对 —— 证书换一次要发版，但通道是可信的。
// 本项目取 (b)：这是「安全默认」而非「方便默认」。Android 侧同样是
// assets/agentlite_sync_ca.pem 随包下发，两端策略一致。

import Foundation
#if canImport(FoundationNetworking)
import FoundationNetworking
#endif
import CryptoKit

// MARK: - 错误

/// 服务端错误信封 `{error, message}`（agentlite-server 的 `writeErr`）。
public struct ServerError: Error, CustomStringConvertible, Sendable {
    public let status: Int
    public let code: String
    public let message: String

    public var description: String { "服务端错误 \(status) [\(code)]: \(message)" }

    /// 令牌失效需要重新登录的情形。调用方据此决定「清 token 并跳登录页」，
    /// 而不是把 401 当普通错误弹一下就完事（那会让用户卡在死循环里）。
    public var needsRelogin: Bool {
        status == 401 || code == "unauthorized" || code == "token_invalid"
    }
}

public enum AgentLiteError: Error, CustomStringConvertible, Sendable {
    case notLoggedIn
    case notBound
    case badURL(String)
    case transport(String)
    case decoding(String)
    case pinningMissing
    case pinningMismatch
    case server(ServerError)

    public var description: String {
        switch self {
        case .notLoggedIn: return "尚未登录账号"
        case .notBound: return "本机尚未绑定到账号"
        case .badURL(let u): return "地址不合法: \(u)"
        case .transport(let m): return "网络错误: \(m)"
        case .decoding(let m): return "响应解析失败: \(m)"
        case .pinningMissing: return "缺少服务端证书 pin —— 拒绝连接"
        case .pinningMismatch: return "服务端证书与 pin 不匹配 —— 拒绝连接（可能是中间人）"
        case .server(let e): return e.description
        }
    }
}

// MARK: - 证书 pin

/// 公钥 pin：比对服务端证书的 SPKI SHA-256（不是整证书哈希）。
/// 用 SPKI 而不是整证书，是因为续签同一把密钥时整证书哈希会变，而公钥没变 ——
/// 减少「换证书就要发版」的次数，同时不降低安全性。
public final class CertificatePinner: NSObject, URLSessionDelegate, @unchecked Sendable {
    private let pinnedSPKISHA256: Set<String>

    /// - Parameter pinnedSPKISHA256: base64 编码的 SPKI SHA-256 指纹集合（轮换期可有多个）。
    public init(pinnedSPKISHA256: Set<String>) {
        self.pinnedSPKISHA256 = pinnedSPKISHA256
    }

    /// 从 DER 证书字节算 SPKI SHA-256（base64）。
    public static func spkiSHA256Base64(derCertificate: Data) -> String? {
        guard let cert = SecCertificateCreateWithData(nil, derCertificate as CFData) else { return nil }
        guard let key = SecCertificateCopyKey(cert) else { return nil }
        guard let spki = SecKeyCopyExternalRepresentation(key, nil) as Data? else { return nil }
        return Data(SHA256.hash(data: spki)).base64EncodedString()
    }

    public func urlSession(
        _ session: URLSession,
        didReceive challenge: URLAuthenticationChallenge,
        completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
    ) {
        guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
              let trust = challenge.protectionSpace.serverTrust,
              let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
              let leaf = chain.first else {
            completionHandler(.performDefaultHandling, nil)
            return
        }

        guard !pinnedSPKISHA256.isEmpty else {
            // 没有 pin 宁可连接失败，也不静默降级到「信任一切」。
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }

        let der = SecCertificateCopyData(leaf) as Data
        guard let got = Self.spkiSHA256Base64(derCertificate: der),
              pinnedSPKISHA256.contains(got) else {
            completionHandler(.cancelAuthenticationChallenge, nil)
            return
        }
        completionHandler(.useCredential, URLCredential(trust: trust))
    }
}

public enum CertificatePinnerSupport {
    /// 从 DER 或 PEM 证书算 SPKI SHA-256（base64）。
    public static func spkiSHA256Base64(certificateFile: String) -> String? {
        guard let raw = FileManager.default.contents(atPath: certificateFile) else { return nil }
        return spkiSHA256Base64(certificateData: raw)
    }

    public static func spkiSHA256Base64(certificateData: Data) -> String? {
        var der = certificateData
        // PEM → DER：去掉头尾与空白再解码。证书常以 PEM 形态分发
        //（agentlite-server/tls/server.crt），直接喂 SecCertificate 会失败。
        if let text = String(data: certificateData, encoding: .utf8), text.contains("BEGIN CERTIFICATE") {
            let b64 = text
                .replacingOccurrences(of: "-----BEGIN CERTIFICATE-----", with: "")
                .replacingOccurrences(of: "-----END CERTIFICATE-----", with: "")
                .components(separatedBy: .whitespacesAndNewlines).joined()
            guard let decoded = Data(base64Encoded: b64) else { return nil }
            der = decoded
        }
        return CertificatePinner.spkiSHA256Base64(derCertificate: der)
    }
}

// MARK: - HTTP 客户端

public actor HTTPClient {
    private let baseURL: URL
    private let session: URLSession
    private var token: String?
    private var deviceID: String?

    /// - Parameters:
    ///   - pinnedSPKISHA256: 允许的服务端证书 SPKI 指纹。**空集合 = 拒绝一切连接**
    ///     （安全默认）。仅当 `allowInsecureLoopback` 为真且地址是环回时才可省 pin。
    ///   - allowInsecureLoopback: 本地联调开关。只有环回地址能配上它，
    ///     否则直接 precondition 失败 —— 防止这条捷径被误带到线上。
    public init(
        baseURL: URL,
        pinnedSPKISHA256: Set<String>,
        allowInsecureLoopback: Bool = false,
        timeout: TimeInterval = 20
    ) {
        self.baseURL = baseURL

        let isLoopback = Self.isLoopback(baseURL)
        if allowInsecureLoopback && !isLoopback {
            preconditionFailure("allowInsecureLoopback 只允许环回地址，收到: \(baseURL)")
        }

        let cfg = URLSessionConfiguration.ephemeral
        cfg.timeoutIntervalForRequest = timeout
        cfg.timeoutIntervalForResource = timeout * 2
        cfg.httpAdditionalHeaders = ["User-Agent": "AgentLite-iOS/\(AgentLiteVersion.string)"]

        if allowInsecureLoopback && isLoopback {
            // 环回不经过任何网络介质，不存在中间人面，因此**不挂 pinner**。
            // 注意：不能靠「挂一个空 pin 集合的 pinner」来省事 —— 那会让
            // CertificatePinner 因「无 pin」而 cancel 每一个挑战，表现为
            // 全部请求失败且错误信息误导为「证书不匹配」。这里显式走无 delegate 分支。
            self.session = URLSession(configuration: cfg)
        } else {
            let pinner = CertificatePinner(pinnedSPKISHA256: pinnedSPKISHA256)
            self.session = URLSession(configuration: cfg, delegate: pinner, delegateQueue: nil)
        }
    }

    /// 是否环回地址。公开出去是因为宿主 App 需要据此决定"用哪个 pin"：
    /// 环回可免 pin（联调），公网地址必须 pin —— 这个判断只应有一份实现。
    public static func isLoopback(_ url: URL) -> Bool {
        guard let host = url.host?.lowercased() else { return false }
        return host == "127.0.0.1" || host == "localhost" || host == "::1" || host == "[::1]"
    }

    /// 只用于「无 pin 的本地联调」—— 显式命名，避免被顺手用进生产路径。
    public static func insecureLocalhost(baseURL: URL) -> HTTPClient {
        HTTPClient(baseURL: baseURL, pinnedSPKISHA256: [], allowInsecureLoopback: true)
    }

    public func setAuth(token: String?, deviceID: String?) {
        self.token = token
        self.deviceID = deviceID
    }

    public var currentToken: String? { token }
    public var currentDeviceID: String? { deviceID }

    // MARK: 请求

    public struct Options: Sendable {
        public var authorized: Bool
        public var deviceScoped: Bool
        public var query: [String: String]

        public init(authorized: Bool = true, deviceScoped: Bool = false, query: [String: String] = [:]) {
            self.authorized = authorized
            self.deviceScoped = deviceScoped
            self.query = query
        }
    }

    public func send<Body: Encodable, Out: Decodable>(
        method: String,
        path: String,
        body: Body?,
        options: Options = Options(),
        as: Out.Type
    ) async throws -> Out {
        let data = try await raw(method: method, path: path, body: body, options: options)
        if Out.self == EmptyResponse.self {
            // swiftlint:disable:next force_cast
            return EmptyResponse() as! Out
        }
        do {
            return try JSONDecoder().decode(Out.self, from: data)
        } catch {
            throw AgentLiteError.decoding("\(error) —— 原始响应: \(String(data: data.prefix(400), encoding: .utf8) ?? "<binary>")")
        }
    }

    /// 只发不解析（`Out == EmptyResponse` 时用）。
    public func send<Body: Encodable>(
        method: String,
        path: String,
        body: Body?,
        options: Options = Options()
    ) async throws {
        _ = try await raw(method: method, path: path, body: body, options: options)
    }

    public struct EmptyResponse: Codable, Sendable { public init() {} }

    private func raw<Body: Encodable>(
        method: String,
        path: String,
        body: Body?,
        options: Options
    ) async throws -> Data {
        guard var comps = URLComponents(url: baseURL.appendingPathComponent(path), resolvingAgainstBaseURL: false) else {
            throw AgentLiteError.badURL(path)
        }
        if !options.query.isEmpty {
            comps.queryItems = options.query.map { URLQueryItem(name: $0.key, value: $0.value) }
        }
        guard let url = comps.url else { throw AgentLiteError.badURL(path) }

        var req = URLRequest(url: url)
        req.httpMethod = method
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")
        if options.authorized {
            guard let token, !token.isEmpty else { throw AgentLiteError.notLoggedIn }
            req.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
        }
        if options.deviceScoped {
            guard let deviceID, !deviceID.isEmpty else { throw AgentLiteError.notBound }
            req.setValue(deviceID, forHTTPHeaderField: "X-Device-Id")
        }
        if let body {
            req.httpBody = try JSONEncoder().encode(body)
        }

        let data: Data
        let response: URLResponse
        do {
            (data, response) = try await session.data(for: req)
        } catch {
            // 证书被 pin 拒掉时 URLSession 报的是笼统的 cancelled —— 这里翻译成可诊断的错误，
            // 否则「中间人」和「网络不通」在日志里长得一模一样。
            let ns = error as NSError
            if ns.domain == NSURLErrorDomain,
               ns.code == NSURLErrorCancelled || ns.code == NSURLErrorServerCertificateUntrusted {
                throw AgentLiteError.pinningMismatch
            }
            throw AgentLiteError.transport("\(ns.localizedDescription) (\(ns.domain) \(ns.code))")
        }

        guard let http = response as? HTTPURLResponse else {
            throw AgentLiteError.transport("非 HTTP 响应")
        }
        guard (200..<300).contains(http.statusCode) else {
            if let env = try? JSONDecoder().decode(ErrorEnvelope.self, from: data) {
                throw AgentLiteError.server(ServerError(status: http.statusCode, code: env.error, message: env.message))
            }
            throw AgentLiteError.server(ServerError(
                status: http.statusCode,
                code: "http_\(http.statusCode)",
                message: String(data: data.prefix(300), encoding: .utf8) ?? ""
            ))
        }
        return data
    }

    private struct ErrorEnvelope: Decodable {
        let error: String
        let message: String
    }
}

public enum AgentLiteVersion {
    public static let string = "0.1.0"
}
