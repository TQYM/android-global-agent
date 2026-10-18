// SPDX-License-Identifier: Apache-2.0
//
// 账号 + 设备绑定。
//
// 与服务端契约一一对应（agentlite-server/main.go:90-100）：
//   POST /api/v1/account/register   → AccountSession
//   POST /api/v1/account/login      → AccountSession
//   GET  /api/v1/account/me         → AccountProfile（含设备列表）
//   POST /api/v1/device/bind        → BindResult
//
// 「登录后必做绑定」是本端有意强制的顺序：服务端大多数接口走 authDeviceMW，
// 缺 X-Device-Id 直接 400（api.go:146）。Android 侧靠启动流程隐式保证，
// iOS 侧把它写进 `login()` 的返回值里——让「登录成功但没有设备 ID」
// 这个中间态在类型上就不存在。

import Foundation

public actor AccountService {
    private let http: HTTPClient
    private let credentials: CredentialStore
    private let secureStore: SecureStore

    public init(http: HTTPClient, credentials: CredentialStore, secureStore: SecureStore) {
        self.http = http
        self.credentials = credentials
        self.secureStore = secureStore
    }

    // MARK: - 注册 / 登录

    public func register(name: String, key: String) async throws -> Credentials {
        try Self.validate(name: name, key: key)
        let session: AccountSession = try await http.send(
            method: "POST",
            path: "/api/v1/account/register",
            body: ["name": name, "key": key],
            options: .init(authorized: false),
            as: AccountSession.self
        )
        return try await adopt(session: session, accountKey: key)
    }

    public func login(name: String, key: String) async throws -> Credentials {
        try Self.validate(name: name, key: key)
        let session: AccountSession = try await http.send(
            method: "POST",
            path: "/api/v1/account/login",
            body: ["name": name, "key": key],
            options: .init(authorized: false),
            as: AccountSession.self
        )
        return try await adopt(session: session, accountKey: key)
    }

    /// 客户端预校验，**不替代**服务端校验（account.go:92）。
    /// 在这里挡一道的理由是省一次往返、并给出中文提示；真正的权威仍在服务端。
    static func validate(name: String, key: String) throws {
        let n = name.lowercased().trimmingCharacters(in: .whitespaces)
        guard n.count >= 3, n.count <= 32 else {
            throw AgentLiteError.server(ServerError(status: 400, code: "bad_input", message: "账号名长度需 3-32"))
        }
        let allowed = CharacterSet(charactersIn: "abcdefghijklmnopqrstuvwxyz0123456789_-")
        guard n.unicodeScalars.allSatisfy({ allowed.contains($0) }) else {
            throw AgentLiteError.server(ServerError(status: 400, code: "bad_input", message: "账号名只允许小写字母、数字、下划线、连字符"))
        }
        guard key.count >= 8, key.count <= 256 else {
            throw AgentLiteError.server(ServerError(status: 400, code: "bad_input", message: "账号密钥至少 8 位"))
        }
    }

    /// 登录成功后的统一收尾：存凭据 → 绑定设备 → 回填 deviceID。
    ///
    /// 顺序刻意如此：先存凭据再绑定。反过来的话，绑定失败时 token 会丢，
    /// 用户得重新登录一次才能重试——把一个可重试的失败放大成一次完整重登。
    private func adopt(session: AccountSession, accountKey: String) async throws -> Credentials {
        var creds = Credentials(
            accountID: session.accountID,
            accountName: session.name,
            token: session.token,
            deviceID: nil,
            accountKey: accountKey
        )
        try credentials.save(creds)
        await http.setAuth(token: creds.token, deviceID: nil, accountID: creds.accountID)

        let bound = try await bindDevice()
        creds.deviceID = bound.deviceID
        try credentials.save(creds)
        await http.setAuth(token: creds.token, deviceID: creds.deviceID, accountID: creds.accountID)
        return creds
    }

    /// 恢复已有登录态（App 冷启调用）。
    @discardableResult
    public func restore() async throws -> Credentials? {
        guard let creds = try credentials.load() else { return nil }
        await http.setAuth(token: creds.token, deviceID: creds.deviceID, accountID: creds.accountID)
        return creds
    }

    public func logout() async throws {
        try credentials.logout()
        await http.setAuth(token: nil, deviceID: nil)
    }

    // MARK: - 设备绑定

    public func bindDevice() async throws -> BindResult {
        let fp = try DeviceIdentity.fingerprint(store: secureStore)
        return try await http.send(
            method: "POST",
            path: "/api/v1/device/bind",
            body: BindRequest(
                fingerprint: fp,
                name: DeviceIdentity.displayName(fingerprint: fp),
                model: PlatformInfo.model,
                brand: PlatformInfo.brand,
                sdk: PlatformInfo.sdkLevel,
                appVersion: AgentLiteVersion.string
            ),
            as: BindResult.self
        )
    }

    private struct BindRequest: Encodable {
        let fingerprint: String
        let name: String
        let model: String
        let brand: String
        let sdk: Int
        let appVersion: String

        enum CodingKeys: String, CodingKey {
            case fingerprint, name, model, brand, sdk
            case appVersion = "app_version"
        }
    }

    // MARK: - 档案（设备列表 / 额度）

    public func profile() async throws -> AccountProfile {
        try await http.send(method: "GET", path: "/api/v1/account/me", body: EmptyBody?.none, as: AccountProfile.self)
    }

    /// 同账号的其它设备（用于「让哪台设备做事」的选择器）。
    /// 过滤掉本机：服务端明确拒绝自己给自己下指令（command.go 的 ErrCommandSelf）。
    public func peerDevices() async throws -> [ServerDevice] {
        let me = await http.currentDeviceID
        return try await profile().peerDevices(excluding: me)
    }
}
