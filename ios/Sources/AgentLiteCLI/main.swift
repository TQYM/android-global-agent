// SPDX-License-Identifier: Apache-2.0
//
// agentlite-ios —— iOS 端的命令行外壳。
//
// 存在的意义（不是「顺手做个 CLI」）：
//   1. **真机以外也能证明协议正确**。核心库不依赖 UIKit，所以同一份代码
//      既进 App，也能在 Mac 上直连服务端跑完整「注册→登录→绑定→下发→领取→回报」。
//   2. **对端可当**。`accept` 子命令用 `LANPeer.acceptInbound` 充当 Android
//      的监听方，验证 iOS 侧封的包 Android 解得开（对应 tests/verify_lan_p2p.sh 的思路）。
//   3. 给 App 生成证书 pin 指纹（`pin` 子命令），免去手抄 base64。

import Foundation
import AgentLiteCore

// MARK: - 极简参数解析

struct Args {
    private var flags: [String: String] = [:]
    private var positional: [String] = []
    private var switches: Set<String> = []

    init(_ argv: [String], booleanFlags: Set<String> = []) {
        var i = 0
        while i < argv.count {
            let a = argv[i]
            if a.hasPrefix("--") {
                let name = String(a.dropFirst(2))
                if booleanFlags.contains(name) {
                    switches.insert(name)
                } else if i + 1 < argv.count {
                    flags[name] = argv[i + 1]
                    i += 1
                } else {
                    switches.insert(name)
                }
            } else {
                positional.append(a)
            }
            i += 1
        }
    }

    func flag(_ n: String) -> String? { flags[n] }
    func flag(_ n: String, default d: String) -> String { flags[n] ?? d }
    func has(_ n: String) -> Bool { switches.contains(n) || flags[n] != nil }
    func pos(_ i: Int) -> String? { i < positional.count ? positional[i] : nil }
}

// MARK: - 输出

func out(_ s: String) { FileHandle.standardOutput.write(Data((s + "\n").utf8)) }
func err(_ s: String) { FileHandle.standardError.write(Data(("✗ " + s + "\n").utf8)) }
func ok(_ s: String) { out("✓ " + s) }
func step(_ s: String) { out("→ " + s) }

func die(_ s: String) -> Never {
    err(s)
    exit(1)
}

// MARK: - 客户端装配

let configDir = ProcessInfo.processInfo.environment["AGENTLITE_HOME"]
    ?? (NSHomeDirectory() + "/.agentlite-ios")

func makeClient(insecure: Bool) -> AgentLiteClient {
    let credsPath = configDir + "/credentials.json"
    let store = FileCredentialStore(path: credsPath)

    let cfg: AgentLiteClient.Config
    if insecure {
        let base = ProcessInfo.processInfo.environment["AGENTLITE_BASE"] ?? "https://127.0.0.1:18443"
        guard let u = URL(string: base) else { die("AGENTLITE_BASE 不是合法 URL: \(base)") }
        cfg = AgentLiteClient.Config(baseURL: u, pinnedSPKISHA256: [], allowInsecureLocalhost: true)
    } else {
        let base = ProcessInfo.processInfo.environment["AGENTLITE_BASE"] ?? "https://8.133.225.105:18443"
        let pin = ProcessInfo.processInfo.environment["AGENTLITE_PIN"] ?? ""
        let pins = Set(pin.split(separator: ",").map { String($0).trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty })
        guard !pins.isEmpty else {
            die("缺少证书 pin。用 `agentlite-ios pin --cert <server.crt>` 取指纹，再设 AGENTLITE_PIN=<base64>")
        }
        guard let u = URL(string: base) else { die("AGENTLITE_BASE 不是合法 URL: \(base)") }
        cfg = AgentLiteClient.Config(baseURL: u, pinnedSPKISHA256: pins)
    }

    return AgentLiteClient(config: cfg, secureStore: store, cursorStore: store)
}

// MARK: - 子命令

let usage = """
agentlite-ios —— AgentLite iOS 端（账号 + 远程控制）

用法:
  agentlite-ios pin --cert <server.crt>            算服务端证书 SPKI 指纹（用于 AGENTLITE_PIN）
  agentlite-ios register --name <账号> --key <密钥>  注册并登录、绑定本机
  agentlite-ios login --name <账号> --key <密钥>     登录并绑定本机
  agentlite-ios whoami                             查看当前账号 / 设备 ID / 额度
  agentlite-ios devices                            列出同账号设备
  agentlite-ios send --to <设备ID> --task "<任务>"   向另一台设备下发指令
  agentlite-ios watch --id <指令ID>                 跟踪一条指令直到出结果
  agentlite-ios collect                            领取并执行本机待办指令（iOS 作为目标设备）
  agentlite-ios lan-sync --host <IP> [--port 18444] 向局域网对端发起加密同步
  agentlite-ios lan-accept --envelope <base64>     解开一条入站信封（验证跨端互解）

环境变量:
  AGENTLITE_BASE   服务端地址（默认 https://8.133.225.105:18443）
  AGENTLITE_PIN    服务端证书 SPKI 指纹（base64，逗号分隔可多个）
  AGENTLITE_HOME   凭据目录（默认 ~/.agentlite-ios）
  AGENTLITE_INSECURE=1  允许环回明文/无 pin（仅本地联调）
"""

let argv = Array(CommandLine.arguments.dropFirst())
guard let command = argv.first else {
    out(usage)
    exit(0)
}
let args = Args(Array(argv.dropFirst()))
let insecure = ProcessInfo.processInfo.environment["AGENTLITE_INSECURE"] == "1"

// MARK: main

switch command {

case "pin":
    guard let path = args.flag("cert") else { die("缺少 --cert <server.crt>") }
    guard let der = try? Data(contentsOf: URL(fileURLWithPath: path)) else {
        die("读不到证书: \(path)（需要 DER 或 PEM 中的 DER 段）")
    }
    // PEM → DER：去掉头尾与换行后 base64 解码
    var derBytes = der
    if let text = String(data: der, encoding: .utf8), text.contains("BEGIN CERTIFICATE") {
        let b64 = text
            .replacingOccurrences(of: "-----BEGIN CERTIFICATE-----", with: "")
            .replacingOccurrences(of: "-----END CERTIFICATE-----", with: "")
            .components(separatedBy: .whitespacesAndNewlines).joined()
        guard let d = Data(base64Encoded: b64) else { die("PEM 解析失败") }
        derBytes = d
    }
    guard let fp = CertificatePinner.spkiSHA256Base64(derCertificate: derBytes) else {
        die("无法从证书提取公钥（SecCertificateCreateWithData 失败）")
    }
    out(fp)

case "register", "login":
    guard let name = args.flag("name"), let key = args.flag("key") else {
        die("缺少 --name / --key")
    }
    let client = makeClient(insecure: insecure)
    let creds: Credentials
    do {
        creds = command == "register"
            ? try await client.register(name: name, key: key)
            : try await client.login(name: name, key: key)
    } catch {
        die("\(error)")
    }
    ok("\(command == "register" ? "注册" : "登录")成功")
    out("  account_id = \(creds.accountID)")
    out("  device_id  = \(creds.deviceID ?? "(未绑定)")")
    out("  凭据目录   = \(configDir)")

case "whoami":
    let client = makeClient(insecure: insecure)
    do {
        guard try await client.bootstrap() != nil else { die("尚未登录（先 login / register）") }
        let p = try await client.profile()
        ok("已登录")
        out("  账号      = \(p.name) (\(p.accountID))")
        out("  额度      = \(p.quotaUsed)/\(p.quota)")
        out("  设备数    = \(p.devices.count)")
        for d in p.devices {
            out("    · \(d.deviceID)  \(d.name ?? "-")  [\(d.brand ?? "?")/\(d.model ?? "?")]")
        }
    } catch { die("\(error)") }

case "devices":
    let client = makeClient(insecure: insecure)
    do {
        guard try await client.bootstrap() != nil else { die("尚未登录") }
        let peers = try await client.peerDevices()
        if peers.isEmpty { out("（没有其它设备；先在 Android 端登录同一账号）") }
        for d in peers {
            let lan = (d.lanIPs?.isEmpty == false) ? " lan=\(d.lanIPs!.joined(separator: ",")):\(d.lanPort ?? 0)" : ""
            out("\(d.deviceID)  \(d.name ?? "-")  [\(d.brand ?? "?")/\(d.model ?? "?")]\(lan)")
        }
    } catch { die("\(error)") }

case "send":
    guard let to = args.flag("to"), let task = args.flag("task") else { die("缺少 --to / --task") }
    let client = makeClient(insecure: insecure)
    do {
        guard try await client.bootstrap() != nil else { die("尚未登录") }
        let c = try await client.sendRemoteTask(toDeviceID: to, task: task)
        ok("已下发")
        out("  command_id = \(c.commandID)")
        out("  status     = \(c.status)")
        out("  提示：5 分钟内未被目标设备领取即过期；用 watch --id \(c.commandID) 跟踪结果")
    } catch { die("\(error)") }

case "watch":
    guard let id = args.flag("id") else { die("缺少 --id") }
    let client = makeClient(insecure: insecure)
    do {
        guard try await client.bootstrap() != nil else { die("尚未登录") }
        let final = try await client.waitForCommand(commandID: id) { c in
            out("  … status=\(c.status) \(c.detail ?? "")")
        }
        ok("终态: \(final.status)  \(final.detail ?? "")")
    } catch let e as AgentLiteError {
        // 404 在这里是**正常**结果之一：服务端在目标设备回报终态后就删除了该指令
        //（command.go:195），下发方此后查不到。把它翻译成人话，别让用户以为是故障。
        if case .server(let se) = e, se.status == 404 {
            out("· 指令已不在服务端：可能已被对方执行并回报（终态 ack 后服务端即删除），或已过期未领取。")
            out("  下发方无法再查到结果 —— 这是服务端设计，不是故障（详见实现手册 §4.3）。")
            exit(0)
        }
        die("\(e)")
    } catch { die("\(error)") }

case "collect":
    let client = makeClient(insecure: insecure)
    do {
        guard try await client.bootstrap() != nil else { die("尚未登录") }
        let report = try await client.collectRemoteTasks()
        ok(report.summary)
        for d in report.details { out("  \(d)") }
    } catch { die("\(error)") }

case "lan-sync":
    guard let host = args.flag("host") else { die("缺少 --host") }
    let port = Int(args.flag("port", default: "18444")) ?? 18444
    let client = makeClient(insecure: insecure)
    do {
        guard try await client.bootstrap() != nil else { die("尚未登录") }
        let res = try await client.syncWithLANPeer(host: host, port: port)
        ok("局域网同步成功（对端已解密并回包）")
        out("  对端 server_seq = \(res.serverSeq.map { "\($0)" } ?? "-")")
        out("  记录数         = \(res.records.count)")
    } catch { die("\(error)") }

case "lan-accept":
    guard let env = args.flag("envelope") else { die("缺少 --envelope <base64>") }
    guard let credsPath = args.flag("creds") ?? Optional(configDir + "/credentials.json"),
          let creds = try? FileCredentialStore(path: credsPath).get("credentials.v1"),
          let c = try? JSONDecoder().decode(Credentials.self, from: creds) else {
        die("读不到凭据（先 login）")
    }
    let peer = LANPeer(accountKey: c.accountKey, accountID: c.accountID, deviceID: c.deviceID ?? "d_unknown")
    do {
        let (from, payload) = try await peer.acceptInbound(envelope: env)
        ok("信封校验通过（AAD + GCM tag + 抗重放）")
        out("  来自设备 = \(from)")
        out("  since    = \(payload.since)")
        out("  tasks    = \(payload.push.tasks.count)  memories = \(payload.push.memories.count)")
    } catch { die("\(error)") }

case "lan-respond":
    guard let env = args.flag("envelope") else { die("缺少 --envelope <base64>") }
    guard let credsPath = args.flag("creds") ?? Optional(configDir + "/credentials.json"),
          let credsData = try? FileCredentialStore(path: credsPath).get("credentials.v1"),
          let c = try? JSONDecoder().decode(Credentials.self, from: credsData) else {
        die("读不到凭据（先 login）")
    }
    let peer = LANPeer(accountKey: c.accountKey, accountID: c.accountID, deviceID: c.deviceID ?? "d_unknown")
    do {
        _ = try await peer.acceptInbound(envelope: env)   // 先校验入站完整性
        let resp = try await peer.respondInbound(serverSeq: Int64(Date().timeIntervalSince1970), records: [])
        out(resp)
    } catch { die("\(error)") }

case "help", "-h":
    out(usage)

default:
    err("未知子命令: \(command)")
    out(usage)
    exit(2)
}
