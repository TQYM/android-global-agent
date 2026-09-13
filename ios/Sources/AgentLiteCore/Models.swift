// SPDX-License-Identifier: Apache-2.0
//
// 服务端 API 的 Swift 映射。
//
// 纪律：这里的字段名**逐字对应** agentlite-server 的 JSON tag，不做「顺手改名」。
// 改名会让两端在编译期都看不出问题，只有联调时才炸——而 iOS 端联调成本最高
// （要装机、要签名），所以宁可在类型层就保持与服务端同构。

import Foundation

// MARK: - 账号

/// 局域网密钥派生参数，由服务端下发（account.go:72-77）。
///
/// - Important: **权威来源是服务端代码，不是文档。** 线上实际字段是 `key_len`；
///   早期按 README 的措辞写成 `key_length` 会让解码直接抛错（本手册落盘前的
///   端到端测试正是这样抓到该偏差的）。改动此结构前请先 `curl /api/v1/account/me`
///   核对真实响应。
public struct LANKeyParams: Codable, Equatable, Sendable {
    public let algorithm: String
    public let iterations: Int
    public let keyLen: Int
    public let saltPrefix: String

    enum CodingKeys: String, CodingKey {
        case algorithm, iterations
        case keyLen = "key_len"
        case saltPrefix = "salt_prefix"
    }
}

/// 注册/登录响应（服务端 `accountPayload`，agentlite-server/api.go:207）。
public struct AccountSession: Codable, Equatable, Sendable {
    public let accountID: String
    public let name: String
    /// 仅注册/登录时返回明文；服务端只存 sha256。
    public let token: String
    public let quota: Int
    public let quotaUsed: Int
    public let createdAt: String?
    public let lanKeyParams: LANKeyParams?
    public let serverTime: String?

    enum CodingKeys: String, CodingKey {
        case accountID = "account_id"
        case name, token, quota
        case quotaUsed = "quota_used"
        case createdAt = "created_at"
        case lanKeyParams = "lan_key_params"
        case serverTime = "server_time"
    }
}

/// 服务端设备档案（store.go:45）。
public struct ServerDevice: Codable, Equatable, Sendable, Identifiable {
    public let deviceID: String
    public let accountID: String
    public let fingerprint: String?
    public let name: String?
    public let model: String?
    public let brand: String?
    public let sdk: Int?
    public let appVersion: String?
    public let createdAt: String?
    public let lastSeenAt: String?
    public let disabled: Bool?
    public let lanIPs: [String]?
    public let lanPort: Int?
    public let publicIP: String?

    public var id: String { deviceID }

    enum CodingKeys: String, CodingKey {
        case deviceID = "device_id"
        case accountID = "account_id"
        case fingerprint, name, model, brand, sdk, disabled
        case appVersion = "app_version"
        case createdAt = "created_at"
        case lastSeenAt = "last_seen_at"
        case lanIPs = "lan_ips"
        case lanPort = "lan_port"
        case publicIP = "public_ip"
    }
}

/// `GET /api/v1/account/me` 响应。
public struct AccountProfile: Codable, Equatable, Sendable {
    public let accountID: String
    public let name: String
    public let quota: Int
    public let quotaUsed: Int
    public let devices: [ServerDevice]
    public let lanKeyParams: LANKeyParams?

    enum CodingKeys: String, CodingKey {
        case accountID = "account_id"
        case name, quota, devices
        case quotaUsed = "quota_used"
        case lanKeyParams = "lan_key_params"
    }
}

public struct BindResult: Codable, Equatable, Sendable {
    public let deviceID: String
    public let accountID: String
    public let created: Bool

    enum CodingKeys: String, CodingKey {
        case deviceID = "device_id"
        case accountID = "account_id"
        case created
    }
}

// MARK: - 同步

/// 上行/下行的同步条目。`data` 是**不透明** JSON —— 服务端不理解其内部结构
/// （api.go:310 注释：「客户端可自由演进」），iOS 端因此也不能对它做假设。
public struct SyncItem: Codable, Equatable, Sendable {
    public let id: String
    public let ts: String
    public let data: JSONValue
    public let originDeviceID: String?

    enum CodingKeys: String, CodingKey {
        case id, ts, data
        case originDeviceID = "origin_device_id"
    }
}

public struct SyncRecord: Codable, Equatable, Sendable {
    public let seq: Int64
    public let deviceID: String
    public let kind: String
    public let id: String
    public let ts: String
    public let data: JSONValue

    enum CodingKeys: String, CodingKey {
        case seq, kind, id, ts, data
        case deviceID = "device_id"
    }
}

/// pull 响应（api.go:370-376）。
///
/// - Important: 服务端字段是 **`server_seq`**（账号流当前水位），不是 `next_seq`。
///   这个偏差是端到端测试跑出来的真实缺陷 —— 文档措辞与实现不一致时，
///   **以服务端代码为准**（`agentlite-server/api.go`）。
public struct PullResult: Codable, Equatable, Sendable {
    public let records: [SyncRecord]
    public let serverSeq: Int64
    public let hasMore: Bool

    enum CodingKeys: String, CodingKey {
        case records
        case serverSeq = "server_seq"
        case hasMore = "has_more"
    }
}

/// push 响应（api.go:349-355 / store.go:332-337）。
///
/// `duplicates` = 按记录 id 去重后跳过的条数（同步是幂等的，
/// 重传同一批不会产生重复记录）。
public struct PushResult: Codable, Equatable, Sendable {
    public let accepted: Int
    public let duplicates: Int
    public let rejected: Int
    public let serverSeq: Int64

    enum CodingKeys: String, CodingKey {
        case accepted, duplicates, rejected
        case serverSeq = "server_seq"
    }
}

// MARK: - 跨设备指令

public struct SyncedCommand: Codable, Equatable, Sendable, Identifiable {
    public let commandID: String
    public let accountID: String
    public let fromDevice: String
    public let toDevice: String
    public let task: String
    public let status: String
    public let detail: String?
    public let createdAt: String?

    public var id: String { commandID }

    enum CodingKeys: String, CodingKey {
        case commandID = "command_id"
        case accountID = "account_id"
        case fromDevice = "from_device"
        case toDevice = "to_device"
        case task, status, detail
        case createdAt = "created_at"
    }
}

public struct CommandEnvelope: Codable, Equatable, Sendable {
    public let command: SyncedCommand
}

public struct CommandListResult: Codable, Equatable, Sendable {
    public let commands: [SyncedCommand]
    public let count: Int
}

/// 指令**服务端状态机**的全集（command.go:35-39）。
///
/// ★ 这是读服务端源码才能确定的事实，**不要照搬 Android 文档的措辞**：
///   服务端的 ack 只接受 `running` / `done` / `failed` 三个值
///   （command.go:166-171），**没有 cancelled**。传别的值会被
///   `ErrBadPayload` 直接拒掉 —— 而且如果客户端把返回码忽略了，
///   表现就是「指令永远停在旧状态」，排查极难（本手册落盘前正是这样发现的）。
public enum SyncedCommandStatus: String, Sendable, Equatable {
    case pending
    case running
    case done
    case failed
    case expired
}

/// 客户端**回传**用的状态。刻意只暴露服务端接受的三个值 ——
/// 用类型挡住「传 cancelled 被静默拒绝」这类错误，比写注释可靠。
public enum CommandStatus: String, Sendable {
    case running
    case done
    /// 失败与被拒绝都归到这里：服务端的语义里「没做成」就是 failed，
    /// 拒绝理由放 `detail`（用户能看懂的是理由，不是状态名）。
    case failed

    /// 是否是终态（终态 ack 后服务端会把指令从内存表里删除，之后查它返回 404）。
    public var isTerminal: Bool { self != .running }
}

/// 上行同步条目。
///
/// 为什么提升为顶层类型而不是嵌在 `SyncService` 里：局域网 P2P 的报文里也要用它
///（`LANPeer.OutboundPayload.push.tasks`），嵌套在另一个 actor 内部的类型会让
/// 两侧的 Codable 推断互相牵连。顶层定义更简单，也让「两端线格式同构」一目了然。
public struct SyncOutgoingItem: Codable, Sendable, Equatable {
    public let id: String
    public let ts: String
    public let data: JSONValue
    /// **必须填**：否则服务端会记成「本机产生」——数据不丢但溯源错了
    ///（SyncClient.java:451 记录过这个坑）。
    public let originDeviceID: String?

    public init(id: String, ts: String, data: JSONValue, originDeviceID: String? = nil) {
        self.id = id
        self.ts = ts
        self.data = data
        self.originDeviceID = originDeviceID
    }

    enum CodingKeys: String, CodingKey {
        case id, ts, data
        case originDeviceID = "origin_device_id"
    }
}

// MARK: - 请求体占位

/// 无请求体的请求用它占位。
///
/// 为什么不用 `Optional<Never>.none`：`Never` 的 `Encodable` conformance
/// 在 macOS 14 / iOS 17 才可用，而本包最低支持 macOS 13 / iOS 16，
/// 会在旧系统上编译告警、并在 Swift 6 语言模式下升级为错误。空结构体更直白。
public struct EmptyBody: Codable, Sendable {
    public init() {}
}

// MARK: - 不透明 JSON
/// 可承载任意 JSON 的容器：同步的 `data` 字段两端都不解释，Swift 侧也不能丢字段。
public enum JSONValue: Codable, Equatable, Sendable {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    public init(from decoder: Decoder) throws {
        let c = try decoder.singleValueContainer()
        if c.decodeNil() { self = .null; return }
        if let b = try? c.decode(Bool.self) { self = .bool(b); return }
        if let n = try? c.decode(Double.self) { self = .number(n); return }
        if let s = try? c.decode(String.self) { self = .string(s); return }
        if let a = try? c.decode([JSONValue].self) { self = .array(a); return }
        if let o = try? c.decode([String: JSONValue].self) { self = .object(o); return }
        throw DecodingError.dataCorruptedError(in: c, debugDescription: "无法识别的 JSON 值")
    }

    public func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .null: try c.encodeNil()
        case .bool(let b): try c.encode(b)
        case .number(let n): try c.encode(n)
        case .string(let s): try c.encode(s)
        case .array(let a): try c.encode(a)
        case .object(let o): try c.encode(o)
        }
    }

    public var objectValue: [String: JSONValue]? {
        if case .object(let o) = self { return o }
        return nil
    }

    public var stringValue: String? {
        if case .string(let s) = self { return s }
        return nil
    }
}
