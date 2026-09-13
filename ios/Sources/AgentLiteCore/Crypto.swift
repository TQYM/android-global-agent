// SPDX-License-Identifier: Apache-2.0
//
// AgentLite iOS —— 密码学原语（必须与 Android/服务端**逐字节**一致）
//
// 这一段是整个 iOS 端最容易「看起来对、实际不通」的地方：PBKDF2 参数或
// AAD 拼接差一个字符，两端就永远解不开，而且失败是静默的（只是 P2P 一直不通）。
// 因此本文件的三条纪律：
//   1. 常量全部照抄 `agentlite-server/README.md §4.1` 与
//      `agent-lite/src/com/dsh/agentlite/LanSync.java:64-67`，不自行「优化」；
//   2. 派生结果必须能通过 `Tests/AgentLiteCoreTests` 里对 Java 的交叉向量；
//   3. 封装格式 = base64( nonce(12B) || ciphertext||tag )，与 Java 侧同序。

import Foundation
import CryptoKit
import CAgentLiteShim

public enum AgentLiteCrypto {

    // MARK: - 协议常量（改动即破坏跨端互操作）

    /// 局域网密钥派生盐前缀。与服务端 `LANKeySaltPrefix`、Java `KDF_SALT_PREFIX` 同值。
    public static let lanSaltPrefix = "agentlite-lan|"
    /// PBKDF2 迭代数。与服务端 `LANKeyIterations=100_000`、Java `KDF_ITERATIONS` 同值。
    public static let lanIterations = 100_000
    /// 派生密钥长度（字节）。32 字节 = AES-256。
    public static let lanKeyLength = 32
    /// AAD 前缀。Java `LanSync.AAD_PREFIX`。
    public static let aadPrefix = "agentlite-lan-v1|"
    /// GCM nonce 长度。Java `NONCE_LEN`。
    public static let nonceLength = 12

    public enum CryptoError: Error, CustomStringConvertible {
        case invalidEnvelopeLength(Int)
        case invalidBase64
        case authFailed          // GCM 校验失败：密文被篡改 / AAD 不匹配 / 密钥不对
        case invalidKeyLength(Int)

        public var description: String {
            switch self {
            case .invalidEnvelopeLength(let n): return "信封过短（\(n) 字节，至少需 >12）"
            case .invalidBase64: return "base64 解码失败"
            case .authFailed: return "GCM 认证失败（密文/AAD/密钥不匹配）"
            case .invalidKeyLength(let n): return "密钥长度必须是 32 字节，实际 \(n)"
            }
        }
    }

    // MARK: - PBKDF2-HMAC-SHA256

    /// 局域网对称密钥派生：两端本地计算，服务端**不经手**该密钥。
    ///
    /// ```
    /// key = PBKDF2-HMAC-SHA256( password = 账号密钥原文,
    ///                           salt     = "agentlite-lan|" + account_id,
    ///                           iter     = 100_000,
    ///                           len      = 32 )
    /// ```
    ///
    /// - Important: 输入是**用户自选的账号密钥**（熵低），所以这里用 PBKDF2 的
    ///   迭代拉伸而不是 HKDF —— 服务端 README §4.1 已论证过同一取舍，iOS 侧照办。
    /// - Note: 用 CommonCrypto 的 `CCKeyDerivationPBKDF` 而不是 CryptoKit：
    ///   CryptoKit 至今没有公开 PBKDF2 API，而 CommonCrypto 在 Apple 全平台可用且是常量时间实现。
    public static func deriveLANKey(accountKey: String, accountID: String) -> SymmetricKey {
        let password = Array(accountKey.utf8)
        let salt = Array((lanSaltPrefix + accountID).utf8)
        var derived = [UInt8](repeating: 0, count: lanKeyLength)

        let status = derived.withUnsafeMutableBufferPointer { out in
            password.withUnsafeBufferPointer { pw in
                salt.withUnsafeBufferPointer { sa in
                    agentlite_pbkdf2_sha256(
                        pw.baseAddress.map { UnsafeRawPointer($0).assumingMemoryBound(to: CChar.self) },
                        password.count,
                        sa.baseAddress,
                        salt.count,
                        UInt32(lanIterations),
                        out.baseAddress,
                        lanKeyLength
                    )
                }
            }
        }
        precondition(status == 0, "PBKDF2 派生失败: \(status)")
        return SymmetricKey(data: Data(derived))
    }

    // MARK: - AES-256-GCM 信封

    /// 封装并加密：返回 `base64( nonce || ciphertext || tag )`。
    ///
    /// AAD 用调用方传入的完整字节（本项目的约定是 `aadPrefix + account_id`，
    /// 见 `LANPeer.inboundAAD` 的注释——为什么 device_id 不进 AAD 有专门理由）。
    public static func seal(key: SymmetricKey, aad: String, plaintext: String) throws -> String {
        let nonce = AES.GCM.Nonce()                       // 12 字节随机，CryptoKit 保证
        let sealed = try AES.GCM.seal(
            Data(plaintext.utf8),
            using: key,
            nonce: nonce,
            authenticating: Data(aad.utf8)
        )
        // CryptoKit 的 `combined` 正是 nonce || ciphertext || tag，与 Java 侧同序；
        // 不自己拼数组，避免顺序写反这种只在解密时才暴露的错误。
        guard let combined = sealed.combined else {
            throw CryptoError.invalidEnvelopeLength(0)
        }
        return combined.base64EncodedString()
    }

    /// 解密并校验 AAD + GCM tag。
    /// - Throws: `CryptoError.authFailed` 表示三种情况之一（篡改/AAD 不符/密钥错）——
    ///   刻意不区分，避免把「密钥对不对」变成可探测的预言机。
    public static func open(key: SymmetricKey, aad: String, envelope: String) throws -> String {
        guard let combined = Data(base64Encoded: envelope) else {
            throw CryptoError.invalidBase64
        }
        guard combined.count > nonceLength else {
            throw CryptoError.invalidEnvelopeLength(combined.count)
        }
        let box: AES.GCM.SealedBox
        do {
            box = try AES.GCM.SealedBox(combined: combined)
        } catch {
            throw CryptoError.invalidEnvelopeLength(combined.count)
        }
        do {
            let plain = try AES.GCM.open(box, using: key, authenticating: Data(aad.utf8))
            guard let s = String(data: plain, encoding: .utf8) else {
                throw CryptoError.authFailed
            }
            return s
        } catch {
            throw CryptoError.authFailed
        }
    }

    /// 信封里的 nonce（base64）。抗重放缓存用它做去重键。
    public static func nonceB64(ofEnvelope envelope: String) -> String? {
        guard let combined = Data(base64Encoded: envelope), combined.count > nonceLength else {
            return nil
        }
        return combined.prefix(nonceLength).base64EncodedString()
    }

    // MARK: - 摘要 / 随机

    public static func sha256Hex(_ s: String) -> String {
        SHA256.hash(data: Data(s.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    public static func randomHex(bytes: Int) -> String {
        var b = [UInt8](repeating: 0, count: bytes)
        // SecRandomCopyBytes 是系统 CSPRNG；不用 UUID 拼凑（熵与格式都不合适）
        let status = SecRandomCopyBytes(kSecRandomDefault, bytes, &b)
        precondition(status == errSecSuccess, "系统随机源不可用")
        return b.map { String(format: "%02x", $0) }.joined()
    }

    public static func constantTimeEquals(_ a: String, _ b: String) -> Bool {
        let x = Array(a.utf8), y = Array(b.utf8)
        guard x.count == y.count else { return false }
        var diff: UInt8 = 0
        for i in 0..<x.count { diff |= x[i] ^ y[i] }
        return diff == 0
    }
}
