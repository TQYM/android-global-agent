// SPDX-License-Identifier: Apache-2.0
//
// 本机设备指纹。
//
// 服务端按 `fingerprint` 去重（store.go:218：「同一 fingerprint 重复绑定视为同一台设备」），
// 因此这个值必须**跨启动稳定**，又**不能是硬件标识符**：
//   - iOS 上 UDID 拿不到，IDFV 会在「同厂商 App 全部卸载」后重置；
//   - 硬件序列号属于不可重置的追踪标识 —— 上报它既过不了审核，也没必要。
// 取舍：自己生成一个随机 ID 存 Keychain。用户重装 App 后 Keychain 项仍在
//（除非用户主动抹掉全部内容），因此重装不会在服务端多出一台幽灵设备。

import Foundation

public enum DeviceIdentity {

    public static let keychainKey = "device.fingerprint.v1"

    /// 取（或首次生成）本机指纹。格式 `ios-<32 hex>` —— 前缀让服务端日志一眼能看出平台，
    /// 便于跨端排查「这条记录是哪台设备产生的」。
    public static func fingerprint(store: SecureStore) throws -> String {
        if let data = try store.get(keychainKey),
           let existing = String(data: data, encoding: .utf8),
           !existing.isEmpty {
            return existing
        }
        let fresh = "ios-\(AgentLiteCrypto.randomHex(bytes: 16))"
        try store.set(Data(fresh.utf8), for: keychainKey)
        return fresh
    }

    /// 默认展示名：`iPhone` / `iPad` + 短后缀，方便用户在设备列表里区分多台机器。
    public static func displayName(fingerprint: String) -> String {
        let suffix = String(fingerprint.suffix(4))
        return "\(PlatformInfo.model)-\(suffix)"
    }
}
