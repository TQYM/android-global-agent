// SPDX-License-Identifier: Apache-2.0
//
// 平台与运行环境探测。
//
// 服务端 `Device` 有 brand/model/sdk 三个字段（agentlite-server/store.go:45-55），
// Android 侧填的是系统 Build.*。iOS 端必须**如实上报**而不是伪装成 Android：
//   1. 「这台设备能不能执行这个任务」在跨设备下发时是决策输入 —— 伪装会让
//      下发方以为目标机能自动点按，实际做不到（iOS 没有该能力，见手册 §1）。
//   2. 服务端 device 表是运维排查的依据，填假值等于把故障现场涂掉。

import Foundation

public enum PlatformInfo {

    /// 当前进程所在平台。测试里用它跳过平台专属断言。
    public static var isIOS: Bool {
        #if os(iOS)
        return true
        #else
        return false
        #endif
    }

    /// 上报给服务端 `brand` 字段。
    public static var brand: String {
        #if os(iOS)
        return "Apple"
        #elseif os(macOS)
        return "Apple-macOS"   // 桌面跑的是测试/对端模拟，不应与真机混淆
        #else
        return "Apple-unknown"
        #endif
    }

    /// 上报给服务端 `model` 字段（如 `iPhone` / `arm64`）。
    ///
    /// 注意：**不取真实机型营销名**。iOS 上拿硬件标识符（`iPhone17,1`）需要
    /// `uname` 的 `hw.machine`，它同时携带机型信息，属于可被用于指纹追踪的数据；
    /// 这里只上报宽泛类别，够服务端做「手机/平板」区分即可。
    public static var model: String {
        #if os(iOS)
        // UIDevice 在 iOS 上可用，但 core 库不导入 UIKit；
        // 用 sysctl 取 hw.machine 的前缀归类。
        let machine = sysctlString("hw.machine") ?? "iOS"
        if machine.hasPrefix("iPhone") { return "iPhone" }
        if machine.hasPrefix("iPad") { return "iPad" }
        if machine.hasPrefix("iPod") { return "iPod" }
        if machine.hasPrefix("Watch") { return "AppleWatch" }
        return machine
        #elseif os(macOS)
        return "macOS"
        #else
        return "unknown"
        #endif
    }

    /// 上报给服务端 `sdk` 字段（Android 用 API level，iOS 借该字段报系统主版本）。
    public static var sdkLevel: Int {
        let v = ProcessInfo.processInfo.operatingSystemVersion
        return v.majorVersion
    }

    public static var osVersionString: String {
        let v = ProcessInfo.processInfo.operatingSystemVersion
        return "\(v.majorVersion).\(v.minorVersion).\(v.patchVersion)"
    }

    private static func sysctlString(_ name: String) -> String? {
        var size = 0
        guard sysctlbyname(name, nil, &size, nil, 0) == 0, size > 0 else { return nil }
        var buf = [CChar](repeating: 0, count: size)
        guard sysctlbyname(name, &buf, &size, nil, 0) == 0 else { return nil }
        return String(cString: buf)
    }
}
