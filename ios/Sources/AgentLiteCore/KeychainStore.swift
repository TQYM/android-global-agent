// SPDX-License-Identifier: Apache-2.0
//
// 本地凭据与配置存储。
//
// 存什么、不存什么（这是本文件最重要的部分）：
//   存   —— 账号令牌（Bearer）、设备 ID、账号 ID、账号密钥（**仅为派生局域网密钥所需**）；
//   不存 —— 局域网对称密钥的明文导出物（只在 Keychain 内以 32 字节数据形态存在，
//           且不进 UserDefaults / 不进日志 / 不进备份导出）。
//
// 为什么账号密钥要留在设备上：局域网 P2P 的对称密钥由它 PBKDF2 派生，
// 服务端**不经手**（README §4.1）。若要每次同步都让用户重输账号密钥，
// 那就等于没有「局域网优先」这个特性。代价是它必须进 Keychain 而不是明文文件。

import Foundation
#if canImport(Security)
import Security
#endif

// MARK: - 存储后端

/// 抽象出来是为了可测：单测用内存实现，真机/模拟器用 Keychain。
public protocol SecureStore: Sendable {
    func set(_ data: Data, for key: String) throws
    func get(_ key: String) throws -> Data?
    func remove(_ key: String) throws
}

public enum SecureStoreError: Error, CustomStringConvertible {
    case keychain(OSStatus)

    public var description: String {
        switch self {
        case .keychain(let s):
            let msg = SecCopyErrorMessageString(s, nil) as String? ?? "未知"
            return "Keychain 操作失败 (\(s)): \(msg)"
        }
    }
}

/// Keychain 后端。
///
/// `kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly`：设备首次解锁后可用、
/// **不随 iCloud 备份迁移**。理由：账号密钥属于「这台设备上的凭据」，
/// 备份到另一台设备会让两台机器共享同一把派生密钥，而服务端无从察觉。
public final class KeychainStore: SecureStore, @unchecked Sendable {
    private let service: String

    public init(service: String = "com.dsh.agentlite.ios") {
        self.service = service
    }

    private func query(_ key: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key,
        ]
    }

    public func set(_ data: Data, for key: String) throws {
        var q = query(key)
        SecItemDelete(q as CFDictionary)   // 先删后写：Keychain 没有 upsert
        q[kSecValueData as String] = data
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        let status = SecItemAdd(q as CFDictionary, nil)
        guard status == errSecSuccess else { throw SecureStoreError.keychain(status) }
    }

    public func get(_ key: String) throws -> Data? {
        var q = query(key)
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: CFTypeRef?
        let status = SecItemCopyMatching(q as CFDictionary, &out)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess else { throw SecureStoreError.keychain(status) }
        return out as? Data
    }

    public func remove(_ key: String) throws {
        let status = SecItemDelete(query(key) as CFDictionary)
        guard status == errSecSuccess || status == errSecItemNotFound else {
            throw SecureStoreError.keychain(status)
        }
    }
}

/// 内存后端（单测 / CLI 演示用）。**不要**在 App 里当默认值用。
public final class InMemoryStore: SecureStore, @unchecked Sendable {
    private var map: [String: Data] = [:]
    private let lock = NSLock()

    public init() {}

    public func set(_ data: Data, for key: String) throws {
        lock.lock(); defer { lock.unlock() }
        map[key] = data
    }

    public func get(_ key: String) throws -> Data? {
        lock.lock(); defer { lock.unlock() }
        return map[key]
    }

    public func remove(_ key: String) throws {
        lock.lock(); defer { lock.unlock() }
        map.removeValue(forKey: key)
    }
}

// MARK: - 凭据

/// 本机凭据快照。
public struct Credentials: Codable, Equatable, Sendable {
    public var accountID: String
    public var accountName: String
    public var token: String
    /// 设备 ID 由服务端在 `/device/bind` 时签发。
    public var deviceID: String?
    /// 账号密钥原文。**只用于派生局域网密钥**，不参与任何网络传输。
    public var accountKey: String

    public init(accountID: String, accountName: String, token: String, deviceID: String? = nil, accountKey: String) {
        self.accountID = accountID
        self.accountName = accountName
        self.token = token
        self.deviceID = deviceID
        self.accountKey = accountKey
    }
}

/// 凭据仓库：Keychain 一条记录装全部（避免多键写入不原子导致半登录状态）。
public final class CredentialStore: @unchecked Sendable {
    private static let key = "credentials.v1"
    private let store: SecureStore

    public init(store: SecureStore = KeychainStore()) {
        self.store = store
    }

    public func save(_ c: Credentials) throws {
        try store.set(try JSONEncoder().encode(c), for: Self.key)
    }

    public func load() throws -> Credentials? {
        guard let data = try store.get(Self.key) else { return nil }
        return try JSONDecoder().decode(Credentials.self, from: data)
    }

    public func clear() throws {
        try store.remove(Self.key)
    }

    /// 退出登录：清凭据。**不**清理本地已同步的缓存数据（那属于用户内容，
    /// 由调用方显式决定是否一并清除）。
    public func logout() throws {
        try clear()
    }
}
