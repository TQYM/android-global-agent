// SPDX-License-Identifier: Apache-2.0
//
// 文件式凭据存储 —— **只给 CLI / 测试用**。
//
// 为什么单独一个实现而不是复用 KeychainStore：
//   CLI 可执行文件没有 App 的 Keychain 访问组与签名身份，Keychain 项会被
//   ACL 拦下（errSecMissingEntitlement）。因此命令行侧落到文件，并要求 0600。
//   App 侧仍走 KeychainStore —— 两者不可互换，故类型上分开，避免误用。

import Foundation

public final class FileCredentialStore: SecureStore, @unchecked Sendable {
    private let path: String
    private let lock = NSLock()

    public init(path: String) {
        self.path = path
    }

    private func load() -> [String: Data] {
        guard let d = FileManager.default.contents(atPath: path),
              let obj = try? JSONDecoder().decode([String: Data].self, from: d) else { return [:] }
        return obj
    }

    private func persist(_ map: [String: Data]) throws {
        let data = try JSONEncoder().encode(map)
        let dir = (path as NSString).deletingLastPathComponent
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        // 先写临时文件再原子替换：避免写入中途崩溃把已有凭据截断成半截 JSON。
        try data.write(to: URL(fileURLWithPath: path), options: [.atomic])
        // 0600：凭据文件不能让同机其它用户读到。
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: path)
    }

    public func set(_ data: Data, for key: String) throws {
        lock.lock(); defer { lock.unlock() }
        var map = load()
        map[key] = data
        try persist(map)
    }

    public func get(_ key: String) throws -> Data? {
        lock.lock(); defer { lock.unlock() }
        return load()[key]
    }

    public func remove(_ key: String) throws {
        lock.lock(); defer { lock.unlock() }
        var map = load()
        map.removeValue(forKey: key)
        try persist(map)
    }
}
