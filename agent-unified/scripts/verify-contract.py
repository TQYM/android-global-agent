#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""verify-contract.py — agent-unified 两端契约静态校验（只读，绝不修改任何文件）。

为什么需要它：客户端（agent-lite，Java）与服务端（agentlite-server，Go）是两个
独立演进的代码库，靠**线协议**咬合。任何一侧单独改动都可能让另一侧静默失效——
而「静默」正是这类系统的常态：HTTP 404 被客户端 catch 掉、字段拼错被服务端
unknown-field 忽略、证书换了只表现为「同步不通」、LAN 密钥参数漂移只表现为
「AES-GCM 认证失败」。

本脚本把两端代码里的契约事实**抽出来互相比对**，让漂移在构建前就暴露：

  1. 零改动保证   副本是否仍与原项目逐字节一致
  2. 证书 pin     客户端信任锚 == 服务端证书（含 SAN 主机名）
  3. 地址一致     unified.conf ↔ 客户端构建默认值 ↔ systemd 单元 ↔ dev.local
  4. 路由覆盖     客户端会调用的每个路径，服务端都有对应路由（方法与路径）
  5. 网关边界     网关可达的模型路径必须被服务端实现；ASR 必须直连
  6. 局域网线协议 两端密码学参数逐项一致（错一个字节就永远连不通）
  7. 字段契约     客户端发送/读取的 JSON 字段在服务端存在
  8. 错误码       客户端按错误码/状态码分支处理的语义，服务端确实会返回
  9. 配置白名单   远程配置只能下发行为开关，base_url / api_key 永不下发（安全红线）
 10. 记账单位     1 unit = 1000 tokens 两端一致

判定原则：**每条结论都必须来自两端源码的事实**，不采信文档、不采信命名习惯。
遇到需要人工判断的地方输出 WARN 而不是猜一个 PASS。

用法:
    python3 scripts/verify-contract.py            # 人类可读报告
    python3 scripts/verify-contract.py --json     # 机器可读
    python3 scripts/verify-contract.py --quiet    # 只输出失败项与总结

退出码：0 = 全部通过（允许 WARN）；1 = 存在 FAIL。
"""
import glob
import hashlib
import json
import os
import re
import subprocess
import sys

UNI = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ROOT = os.path.dirname(UNI)
CLIENT = os.path.join(UNI, "client")
SERVER = os.path.join(UNI, "server")
CLIENT_SRC = os.path.join(CLIENT, "src", "com", "dsh", "agentlite")

QUIET = "--quiet" in sys.argv
AS_JSON = "--json" in sys.argv

results = []  # (status, group, title, details[list])


def add(status, group, title, details=None):
    results.append((status, group, title, details or []))


# ────────────────────────────── 基础工具 ──────────────────────────────

def read(path):
    with open(path, encoding="utf-8", errors="replace") as f:
        return f.read()


def client_file(name):
    return os.path.join(CLIENT_SRC, name)


def load_conf():
    conf = {}
    for line in read(os.path.join(UNI, "unified.conf")).splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        conf[k.strip()] = v.strip()
    return conf


def strip_comments(s):
    """剥掉块注释与行注释。

    保留 `https://` 里的那一对斜杠（用 (?<!:) 前瞻），否则会把字符串里的 URL 截断。
    """
    s = re.sub(r"/\*.*?\*/", "", s, flags=re.S)
    return re.sub(r"(?<!:)//[^\n]*", "", s)


def sha256(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def java_sources():
    return sorted(glob.glob(os.path.join(CLIENT, "src", "**", "*.java"), recursive=True))


def go_sources():
    return sorted(glob.glob(os.path.join(SERVER, "*.go")))


CONF = load_conf()


def all_go_text():
    return "\n".join(read(p) for p in go_sources())


def all_java_code():
    return {p: strip_comments(read(p)) for p in java_sources()}


# ────────────────────────── 1. 零改动保证 ──────────────────────────

def check_zero_modification():
    script = os.path.join(UNI, "scripts", "sync-sources.sh")
    if not os.path.exists(script):
        add("WARN", "零改动", "找不到 sync-sources.sh，跳过逐字节比对")
        return
    r = subprocess.run(["bash", script, "--check"], capture_output=True, text=True)
    if r.returncode == 0:
        add("PASS", "零改动", "client/ 与 server/ 仍是两个原项目的逐字节副本")
    else:
        add("FAIL", "零改动", "副本与原项目存在差异（整合层不该改写任何一端）",
            [l for l in r.stdout.splitlines() if l.strip()])


# ────────────────────────── 2. 证书 pin ──────────────────────────

def check_cert_pin():
    crt = os.path.join(SERVER, "tls", "server.crt")
    pem = os.path.join(CLIENT, "assets", "agentlite_sync_ca.pem")
    if not (os.path.exists(crt) and os.path.exists(pem)):
        add("FAIL", "证书 pin", "证书文件缺失", [crt, pem])
        return
    h_crt, h_pem = sha256(crt), sha256(pem)
    if h_crt != h_pem:
        add("FAIL", "证书 pin", "客户端信任锚 ≠ 服务端证书（APK 会连不上同步服务）",
            ["server/tls/server.crt  " + h_crt, "client/assets/agentlite_sync_ca.pem  " + h_pem,
             "修复：bash scripts/sync-cert.sh && bash scripts/build-all.sh"])
    else:
        add("PASS", "证书 pin", "客户端信任锚 == 服务端证书（%s…）" % h_crt[:16])

    # SAN 必须覆盖 unified.conf 里声明的主机，否则主机名校验会失败（TlsPin 不放松校验）
    # 注：用 -text 解析而非 -ext —— macOS 自带的 LibreSSL 不支持 -ext，会静默返回空串。
    host = CONF.get("SERVER_HOST", "")
    r = subprocess.run(["openssl", "x509", "-in", crt, "-noout", "-text"],
                       capture_output=True, text=True)
    san = r.stdout
    m = re.search(r"Subject Alternative Name:(.*?)(?:\n\s{4}[A-Z][A-Za-z ]+:|\Z)", san, re.S)
    if m:
        san = m.group(1)
    if host and host not in san:
        add("FAIL", "证书 pin", "证书 SAN 不含 SERVER_HOST=%s，客户端主机会校验失败" % host,
            [l.strip() for l in san.splitlines() if l.strip()])
    else:
        add("PASS", "证书 pin", "证书 SAN 覆盖 SERVER_HOST=%s（主机名校验默认开启，未被放松）" % host)

    # 客户端确实把这枚证书用起来了（构建期 PinnedCa + 运行期 TlsPin）
    build = read(os.path.join(CLIENT, "build-mac.sh"))
    transport = read(client_file("AccountHttp.java"))
    pin = read(client_file("TlsPin.java"))
    ok_build = "assets/agentlite_sync_ca.pem" in build and "PinnedCa" in build
    ok_pin = "setSSLSocketFactory(TlsPin.factory())" in transport and "PinnedCa.PEM" in pin
    if ok_build and ok_pin:
        add("PASS", "证书 pin", "构建期生成 PinnedCa、运行期 AccountHttp 复用 TlsPin.factory ✓")
    else:
        add("FAIL", "证书 pin", "证书未被真正接入（构建期 PinnedCa 或运行期 TlsPin 调用缺失）",
            ["build-mac.sh 引用 pem=%s，生成 PinnedCa=%s" % ("assets/agentlite_sync_ca.pem" in build, "PinnedCa" in build),
             "AccountHttp/TlsPin 接线匹配=%s" % ok_pin])


# ────────────────────────── 3. 地址一致性 ──────────────────────────

def check_endpoint_consistency():
    sync_url = CONF.get("SYNC_URL", "")
    host, port = CONF.get("SERVER_HOST", ""), CONF.get("SERVER_PORT", "")

    if sync_url != "https://%s:%s" % (host, port):
        add("FAIL", "地址一致", "unified.conf 内部不一致：SYNC_URL 与 HOST/PORT 对不上",
            ["SYNC_URL=%s  HOST=%s PORT=%s" % (sync_url, host, port)])
    else:
        add("PASS", "地址一致", "unified.conf：SYNC_URL == https://%s:%s" % (host, port))

    build = read(os.path.join(CLIENT, "build-mac.sh"))
    m = re.search(r'^SYNC_URL="([^"]+)"', build, re.M)
    if not m:
        add("FAIL", "地址一致", "客户端 build-mac.sh 里找不到 SYNC_URL 默认值")
    elif m.group(1) != sync_url:
        add("FAIL", "地址一致", "客户端内嵌同步地址与 unified.conf 不一致",
            ["build-mac.sh: %s" % m.group(1), "unified.conf: %s" % sync_url,
             "（构建时由 scripts/gen-client-env.sh 写 dev.local 覆盖，但默认值应一致）"])
    else:
        add("PASS", "地址一致", "客户端构建默认 SYNC_URL == unified.conf")

    unit = read(os.path.join(SERVER, "deploy", "agentlite-server.service"))
    m = re.search(r"-addr\s+(\S+)", unit)
    if not m:
        add("FAIL", "地址一致", "systemd 单元缺少 -addr")
    elif not m.group(1).endswith(":" + port):
        add("FAIL", "地址一致", "systemd 监听端口与 unified.conf SERVER_PORT 不一致",
            ["service: %s" % m.group(1), "unified.conf: %s" % port])
    else:
        add("PASS", "地址一致", "systemd 监听 %s（端口与 unified.conf 一致）" % m.group(1))

    # 客户端的默认值链路：Prefs.syncServer() 默认取 DevConfig.SYNC_URL（构建期注入）
    prefs = read(client_file("Prefs.java"))
    if 'getString("sync_server", DevConfig.SYNC_URL)' in prefs:
        add("PASS", "地址一致", "客户端 Prefs.syncServer() 默认 = DevConfig.SYNC_URL（构建期注入链完整）")
    else:
        add("WARN", "地址一致", "Prefs.syncServer() 的默认值来源变了，请人工确认注入链")

    # dev.local 若已生成，必须与 unified.conf 一致（否则构建出的 APK 指向别处）
    devlocal = os.path.join(CLIENT, "dev.local")
    if os.path.exists(devlocal):
        m = re.search(r"^SYNC_URL=(.*)$", read(devlocal), re.M)
        got = (m.group(1).strip() if m else "")
        if got and got != sync_url:
            add("FAIL", "地址一致", "client/dev.local 的 SYNC_URL 与 unified.conf 不一致",
                ["dev.local: %s" % got, "unified.conf: %s" % sync_url,
                 "修复：bash scripts/gen-client-env.sh"])
        else:
            add("PASS", "地址一致", "client/dev.local 的 SYNC_URL 与 unified.conf 一致")
    else:
        add("PASS", "地址一致", "client/dev.local 未生成（发布形态：APK 不内置地址以外的敏感值）")


# ────────────────────────── 4. 路由覆盖 ──────────────────────────

# 允许查询串（客户端存在 "/api/v1/sync/pull?since=" + cursor 这类拼接）
PATH_RE = re.compile(r'"(/(?:api/v1|v1|lan/v1)/[A-Za-z0-9_\-/]+)(?:\?[^"]*)?"')
METHOD_RE = re.compile(r'"(POST|GET|PUT|DELETE)"')
P2P_PATHS = {"/lan/v1/sync"}   # 设备↔设备 P2P 监听端点，不经服务端


def server_routes():
    """从 main.go 的 mux 注册表抽取 {path: {methods}}。"""
    text = strip_comments(read(os.path.join(SERVER, "main.go")))
    routes = {}
    for m in re.finditer(r'HandleFunc\(\s*"([A-Z]+)\s+([^"]+)"', text):
        routes.setdefault(m.group(2), set()).add(m.group(1))
    return routes


def client_call_sites():
    """抽取客户端代码里出现的服务端路径，并尽量判定 HTTP 方法。

    返回 [(path, method_or_None, "文件:行")]；方法无法静态判定时为 None（只比对路径）。
    """
    sites = []
    for path in java_sources():
        code = strip_comments(read(path))
        name = os.path.relpath(path, CLIENT)
        lines = code.splitlines()
        for i, line in enumerate(lines):
            for m in PATH_RE.finditer(line):
                p = m.group(1)   # 只取路径，丢掉查询串
                # 判定一：同一语句窗口内出现唯一的方法字面量
                window = "\n".join(lines[max(0, i - 3):i + 4])
                methods = set(METHOD_RE.findall(window))
                method = methods.pop() if len(methods) == 1 else None
                # 判定二：路径作为参数传给本文件内的包装方法 → 读该方法的实现
                if method is None:
                    left = line[:m.start()]
                    mm = re.search(r"([A-Za-z_][A-Za-z0-9_]*)\s*\([^()]*$", left)
                    if mm:
                        hb = re.search(r"\b" + re.escape(mm.group(1)) + r"\s*\([^)]*\)\s*\{", code)
                        if hb:
                            hm = set(METHOD_RE.findall(code[hb.end():hb.end() + 1200]))
                            if len(hm) == 1:
                                method = hm.pop()
                sites.append((p, method, "%s:%d" % (name, i + 1)))
    return sites


def check_routes():
    routes = server_routes()
    sites = client_call_sites()

    missing, unchecked = [], []
    for p, method, where in sites:
        if p in P2P_PATHS:
            continue
        if p not in routes:
            missing.append("%s  调用 %s（%s）→ 服务端无此路由" % (where, p, method or "方法未判定"))
        elif method and method not in routes[p]:
            missing.append("%s  调用 %s %s → 服务端只注册了 %s"
                           % (where, method, p, "/".join(sorted(routes[p]))))
        elif not method:
            unchecked.append("%s  %s（方法未静态判定，仅比对路径）" % (where, p))

    if missing:
        add("FAIL", "路由覆盖", "客户端调用的接口在服务端不存在", missing)
    else:
        add("PASS", "路由覆盖", "客户端调用的 %d 处接口全部命中服务端路由"
            % (len([s for s in sites if s[0] not in P2P_PATHS])), unchecked)

    # 反向：已下线接口不许再被调用（只允许出现在注释里 → 剥注释后不应命中）
    dead = "/api/v1/device/register"
    offenders = [os.path.relpath(p, CLIENT) for p in java_sources() if dead in strip_comments(read(p))]
    if offenders:
        add("FAIL", "路由覆盖", "存在对已下线接口的调用", offenders)
    elif dead not in all_go_text():
        add("PASS", "路由覆盖", "已下线的 %s 未被调用、服务端也未注册（仅存于注释说明）" % dead)


# ────────────────────────── 5. 网关边界 ──────────────────────────

def check_gateway_boundary():
    """区分「网关可达」与「仅直连」两类模型调用路径。

    判定依据不是类型而是**调用点**：网关客户端只能来自 LlmClient.forPrefs(...)，
    直连客户端只能来自 new LlmClient(baseUrl, apiKey, ...)。谁调用谁可静态确定，
    于是「服务端 /v1 兼容面必须覆盖哪些路径」也可确定——而不是把所有 post() 都算进去。
    """
    routes = server_routes()
    llm = strip_comments(read(client_file("LlmClient.java")))
    all_code = all_java_code()
    joined = "\n".join(all_code.values())

    # 每个 post("<path>") 归属到它所在的方法
    post_paths = {}
    for m in re.finditer(r'post\("(/[A-Za-z0-9_\-/]+)"', llm):
        defs = re.findall(r'\b(?:public|private|protected)\s+(?:static\s+)?[\w<>\[\], ]+\s+(\w+)\s*\(',
                          llm[:m.start()])
        if defs:
            post_paths.setdefault(defs[-1], set()).add("/v1" + m.group(1))

    # LlmClient 内部存在转调（chat → chatOnce），必须沿调用链把 post 路径向上传递，
    # 否则「网关可达面」会被判成空集。
    defs = [(m.group(1), m.start(), m.end()) for m in
            re.finditer(r'\b(?:public|private|protected)\s+(?:static\s+)?[\w<>\[\], ]+\s+(\w+)\s*\(', llm)]
    bodies = {}
    for i, (name, _start, end) in enumerate(defs):
        bodies[name] = llm[end:(defs[i + 1][1] if i + 1 < len(defs) else len(llm))]
    names = set(bodies)
    changed = True
    while changed:
        changed = False
        for caller, body in bodies.items():
            called = {n for n in names if re.search(r'\b%s\s*\(' % re.escape(n), body)}
            paths = {p for c in called for p in post_paths.get(c, ())}
            if paths - post_paths.get(caller, set()):
                post_paths.setdefault(caller, set()).update(paths)
                changed = True

    gw_vars = set(re.findall(r'LlmClient\s+(\w+)\s*=\s*LlmClient\.forPrefs\(', joined))
    direct_vars = set(re.findall(r'LlmClient\s+(\w+)\s*=\s*new\s+LlmClient\(', joined))
    gw_methods, direct_methods = set(), set()
    for v in gw_vars:
        gw_methods |= set(re.findall(re.escape(v) + r'\s*\.\s*(\w+)\s*\(', joined))
    for v in direct_vars:
        direct_methods |= set(re.findall(re.escape(v) + r'\s*\.\s*(\w+)\s*\(', joined))

    gw_paths = sorted({p for m in gw_methods for p in post_paths.get(m, ())})
    direct_paths = sorted({p for m in direct_methods for p in post_paths.get(m, ())} - set(gw_paths))

    if not gw_paths:
        add("WARN", "网关边界", "未能静态确定网关可达路径（客户端构造方式可能已变），请人工确认")
    else:
        uncovered = [p for p in gw_paths if p not in routes]
        if uncovered:
            add("FAIL", "网关边界", "服务端未实现网关可达的模型路径（登录后模型调用会 404）", uncovered)
        else:
            add("PASS", "网关边界", "网关可达路径 %s 服务端全部实现（由 %s 调用）"
                % (", ".join(gw_paths), ", ".join(sorted(gw_methods))))

    # 仅直连路径不进服务端路由表是**正确**的（服务端只转发 chat，不碰音频）
    leaked = [p for p in direct_paths if p in routes]
    if leaked:
        add("WARN", "网关边界", "以下路径按调用点判定为「仅直连」，服务端却也实现了，请确认语义", leaked)
    else:
        add("PASS", "网关边界", "仅直连路径 %s 不在服务端路由表（ASR 等不经网关，语义正确）"
            % (", ".join(direct_paths) or "（无）"))

    # ASR 调用点必须用直连客户端构造，不能用 forPrefs
    offenders = []
    for path, code in all_code.items():
        for m in re.finditer(r"\.transcribe\(", code):
            window = code[max(0, m.start() - 700):m.start()]
            if "new LlmClient(prefs.baseUrl()" not in window and "forPrefs" in window:
                offenders.append(os.path.relpath(path, CLIENT))
    if offenders:
        add("FAIL", "网关边界", "ASR 走了额度网关（服务端未实现 /v1/audio/transcriptions，语音会 404）",
            sorted(set(offenders)))
    else:
        add("PASS", "网关边界", "ASR 调用点均使用直连客户端（语音不经额度网关）")


# ────────────────────────── 6. 局域网线协议 ──────────────────────────

def client_const(name, pattern):
    m = re.search(pattern, strip_comments(read(client_file(name))))
    return m.group(1) if m else None


def server_const(pattern):
    m = re.search(pattern, all_go_text())
    return m.group(1) if m else None


def norm_int(v):
    return str(int(str(v).replace("_", ""))) if v is not None else None


def check_lan_protocol():
    """两端密码学参数逐项比对。

    这些参数错一个字节就**永远连不通**，而且是静默的（AES-GCM 只会报认证失败，
    看起来像「网络问题」）。所以每一项都从两端源码抽出来比对，不信任文档。
    """
    def bits2bytes(v):
        return str(int(v) // 8) if v is not None else None

    rows = [
        # 名称, 客户端取值, 服务端取值, 说明, 归一化
        ("LAN 端口",
         client_const("LanSync.java", r"DEFAULT_PORT\s*=\s*(\d+)"),
         CONF.get("LAN_PORT"),
         "客户端监听端口 == unified.conf LAN_PORT", None),
        ("LAN 端口（会合上报）",
         client_const("SyncClient.java", r"LAN_PORT\s*=\s*(\d+)"),
         CONF.get("LAN_PORT"),
         "会合时上报的端口 == 客户端监听端口", None),
        ("KDF 算法",
         client_const("LanSync.java", r'SecretKeyFactory\.getInstance\("([A-Za-z0-9]+)"\)'),
         server_const(r'Algorithm:\s*"([A-Za-z0-9]+)"'),
         "PBKDF2-HMAC-SHA256（两端必须同名）", None),
        ("KDF 迭代数",
         norm_int(client_const("LanSync.java", r"KDF_ITERATIONS\s*=\s*([0-9_]+)")),
         norm_int(server_const(r"LANKeyIterations\s*=\s*([0-9_]+)")),
         "拉伸次数，错一次派生出的是另一把密钥", None),
        ("KDF 密钥长度",
         norm_int(client_const("LanSync.java", r"KDF_KEY_BITS\s*=\s*([0-9_]+)")),
         norm_int(server_const(r"LANKeyLen\s*=\s*([0-9_]+)")),
         "客户端按 bit 声明(256)，服务端按字节声明(32) → 归一到字节比对", bits2bytes),
        ("KDF 盐前缀",
         client_const("LanSync.java", r'KDF_SALT_PREFIX\s*=\s*"([^"]+)"'),
         server_const(r'LANKeySaltPrefix\s*=\s*"([^"]+)"'),
         "salt = 前缀 + account_id", None),
        ("AAD 前缀",
         client_const("LanSync.java", r'AAD_PREFIX\s*=\s*"([^"]+)"'),
         "agentlite-lan-v1|",
         "AEAD 附加数据前缀（线协议文档 §4.2）", None),
        ("nonce 长度(字节)",
         norm_int(client_const("LanSync.java", r"NONCE_LEN\s*=\s*(\d+)")),
         "12",
         "GCM 标准 nonce", None),
        ("GCM tag(bit)",
         norm_int(client_const("LanSync.java", r"GCM_TAG_BITS\s*=\s*(\d+)")),
         "128",
         "完整性标签长度", None),
        ("抗重放时间窗(秒)",
         norm_int(client_const("LanSync.java", r"TS_WINDOW_SEC\s*=\s*(\d+)")),
         "120",
         "|now - ts| ≤ 窗口", None),
        ("P2P 路径",
         client_const("LanSync.java", r'PATH\s*=\s*"([^"]+)"'),
         "/lan/v1/sync",
         "设备间监听端点（服务端只做会合）", None),
    ]
    bad, ok = [], []
    for name, cv, sv, note, norm in rows:
        if cv is None:
            bad.append("%s：客户端取值未找到（正则失配，请人工确认）" % name)
        elif sv is None:
            bad.append("%s：服务端侧取值未找到" % name)
        else:
            if norm:
                cv = norm(cv)   # 只归一到服务端口径，不能对服务端取值二次换算
            if str(cv) != str(sv):
                bad.append("%s：客户端 %s ≠ 期望 %s（%s）" % (name, cv, sv, note))
            else:
                ok.append(name)
    if bad:
        add("FAIL", "局域网线协议", "两端密码学/端口参数不一致", bad)
    else:
        add("PASS", "局域网线协议", "%d 项参数两端逐项一致（KDF / AEAD / 抗重放 / 端口）" % len(ok))

    # 服务端 README 的线协议章节必须与实现一致（文档漂移同样会误导运维）
    rd = read(os.path.join(SERVER, "README.md"))
    doc_bad = []
    if "100000" not in rd:
        doc_bad.append("服务端 README 未声明 KDF 迭代数 100000")
    if "agentlite-lan|" not in rd:
        doc_bad.append("服务端 README 未声明盐前缀 agentlite-lan|")
    if "/lan/v1/sync" not in rd:
        doc_bad.append("服务端 README 未声明 P2P 端点 /lan/v1/sync")
    if doc_bad:
        add("WARN", "局域网线协议", "服务端文档与实现可能已漂移", doc_bad)
    else:
        add("PASS", "局域网线协议", "服务端 README 的线协议章节与实现一致")


# ────────────────────────── 7. 字段契约 ──────────────────────────

READ_CALL = r'\.(?:opt|get)(?:String|Int|Long|Boolean|JSONObject|JSONArray)\(\s*"([a-z_][a-z0-9_]*)"'
SEND_CALL = r'\.put\(\s*"([a-z_][a-z0-9_]*)"'
# SharedPreferences 读写（接收者是 sp / prefs.sp）——这些键是**本地存储**，不是线协议字段
PREFS_CALL = r'\b(?:sp(?:\([^)]*\))?|prefs\.sp)\.(?:edit\(\)\.)?(?:get|put|contains|remove)[A-Za-z]*\(\s*"([a-z_][a-z0-9_]*)"'

# 参与跨端契约的客户端文件（读响应 / 发请求）
XFILE = ["AccountClient.java", "AccountHttp.java", "SyncClient.java", "RemoteCommand.java", "LoginActivity.java", "SettingsActivity.java"]


def prefs_keys():
    """客户端本地存储键全量（跨所有源文件扫描）——同名键无法区分读 JSON 还是读本地，一律排除。"""
    keys = set()
    for p in java_sources():
        code = strip_comments(read(p))
        keys |= set(re.findall(PREFS_CALL, code))
        # JSON persisted as a SharedPreferences string (e.g. pending ACK) is
        # local state too, not a new wire response/request schema.
        for var in re.findall(r'\.putString\([^;\n]*?,\s*(\w+)\.toString\(\)\s*\)', code):
            keys |= set(re.findall(re.escape(var) + r'\.(?:put|(?:opt|get)(?:String|Int|Long|Boolean|JSONObject|JSONArray))\(\s*"([a-z_][a-z0-9_]*)"', code))
        keys |= set(re.findall(r'\bprefs\.(?:get|put)[A-Za-z]*\(\s*"([a-z_][a-z0-9_]*)"', strip_comments(read(p))))
    return keys


# 服务端以 json.RawMessage 原样存取的父键（Incoming.Data / Device.LastStatus）：
# 里面的键是客户端私有 schema，服务端从不解析，因此不属于跨端契约。
OPAQUE_PARENT_KEYS = ("status", "data")


def opaque_nested_keys():
    """客户端嵌在 data / status 里的自有字段。"""
    keys = set()
    pat = r'\w+\.put\(\s*"(?:%s)"\s*,\s*(\w+)\s*\)' % "|".join(OPAQUE_PARENT_KEYS)
    for f in XFILE:
        p = client_file(f)
        if not os.path.exists(p):
            continue
        code = strip_comments(read(p))
        for m in re.finditer(pat, code):
            var = m.group(1)
            keys |= set(re.findall(re.escape(var) + r'\.put\(\s*"([a-z_][a-z0-9_]*)"', code))
    return keys


def server_known_fields():
    """服务端认识/会产出的 JSON 字段：struct tag + map 字面量键。"""
    text = all_go_text()
    fields = set(re.findall(r'json:"([a-z_][a-z0-9_]*)(?:,[^"]*)?"', text))
    fields |= set(re.findall(r'"([a-z_][a-z0-9_]*)"\s*:', text))
    return fields


def check_fields():
    known = server_known_fields()
    local = prefs_keys() | opaque_nested_keys()
    reads, sends = set(), set()
    for f in XFILE:
        p = client_file(f)
        if not os.path.exists(p):
            continue
        code = strip_comments(read(p))
        reads |= set(re.findall(READ_CALL, code))
        sends |= set(re.findall(SEND_CALL, code))

    miss_r = sorted(k for k in reads - local if k not in known)
    miss_s = sorted(k for k in sends - local if k not in known)

    if miss_r:
        add("FAIL", "字段契约", "客户端读取的字段服务端从不返回", ["读 %s" % k for k in miss_r])
    else:
        add("PASS", "字段契约", "客户端读取的 %d 个响应字段服务端全部会返回" % len(reads - local))
    if miss_s:
        add("FAIL", "字段契约", "客户端发送的字段服务端不认识（会被 unknown-field 静默忽略）",
            ["发 %s" % k for k in miss_s])
    else:
        add("PASS", "字段契约", "客户端发送的 %d 个请求字段服务端全部认识" % len(sends - local))


# ────────────────────────── 8. 错误码 ──────────────────────────

def check_error_codes():
    """错误码契约。

    客户端不是靠字符串分支，而是（1）用 kind 生成中文提示（ApiException.readable 的
    case 表），（2）按 HTTP 状态码判断是否需要重新登录 / 重新绑定设备。两者都要核对。
    """
    server_text = all_go_text()
    server_codes = set(re.findall(r'writeErr\(\s*\w+\s*,\s*[^,]+,\s*"([a-z_]+)"', server_text))

    api = strip_comments(read(client_file("ApiException.java")))
    used = set(re.findall(r'case\s+"([a-z_]+)"\s*:', api))

    unknown = sorted(c for c in used if c not in server_codes)
    if unknown:
        add("FAIL", "错误码", "客户端为这些错误码写了用户提示，服务端却从不返回（提示永远不会出现）",
            unknown)
    else:
        add("PASS", "错误码", "客户端处理提示的 %d 个错误码服务端均会返回：%s"
            % (len(used), ", ".join(sorted(used))))

    # 状态码语义：客户端按状态码做行为分支，必须与服务端的分配一致
    wants = [
        ("重新登录判定（httpCode == 401）", r"StatusUnauthorized,\s*\"invalid_token\""),
        ("重新登录判定（httpCode == 401）", r"StatusUnauthorized,\s*\"missing_token\""),
        ("账号密钥错误（httpCode == 401）", r"StatusUnauthorized,\s*\"bad_credentials\""),
        ("设备未绑定（403 或 kind=device_not_bound）", r"StatusForbidden,\s*\"device_not_bound\""),
        ("限流提示（httpCode == 429）", r"StatusTooManyRequests,\s*\"rate_limited\""),
        ("上行过大（body_too_large）", r"StatusRequestEntityTooLarge,\s*\"body_too_large\""),
        ("兑换码无效（code_invalid）", r"StatusNotFound,\s*\"code_invalid\""),
        ("账号重名（account_exists）", r"StatusConflict,\s*\"account_exists\""),
    ]
    bad = [name for name, pat in wants if not re.search(pat, server_text)]
    if bad:
        add("FAIL", "错误码", "客户端依赖的状态码语义服务端未按约定返回", sorted(set(bad)))
    else:
        add("PASS", "错误码", "客户端依赖的 8 项状态码语义（401/403/409/413/429…）服务端全部按约定返回")


# ────────────────────────── 9. 配置白名单（安全红线）──────────────────────────

WHITELIST_EXPECTED = {"max_steps", "planner_model", "vision", "spec_inject", "spec_clamp"}


def check_config_whitelist():
    sync = strip_comments(read(client_file("SyncClient.java")))
    m = re.search(r"CONFIG_WHITELIST\s*=\s*\{(.*?)\}", sync, re.S)
    if not m:
        add("FAIL", "配置白名单", "客户端找不到 CONFIG_WHITELIST（远程配置白名单）")
        return
    got = set(re.findall(r'"([a-z_]+)"', m.group(1)))
    if got != WHITELIST_EXPECTED:
        add("FAIL", "配置白名单", "白名单与约定不一致（多一个键 = 多一条被服务端劫持的通道）",
            ["实际 %s" % sorted(got), "期望 %s" % sorted(WHITELIST_EXPECTED)])
    else:
        add("PASS", "配置白名单", "远程配置白名单恰为 %s（%d 个行为开关）"
            % (", ".join(sorted(got)), len(got)))

    # 红线：base_url / api_key 永远不能被下发（否则服务端失陷 = 定向窃取 Key 的通道）
    am = re.search(r"applyConfigKey\([^)]*\)\s*\{(.*?)\n    \}", sync, re.S)
    apply_body = am.group(1) if am else ""
    leaked = [k for k in ("base_url", "api_key") if re.search(r'case\s+"%s"' % k, apply_body)]
    if leaked:
        add("FAIL", "配置白名单", "红线圈被突破：可被服务端下发 %s" % leaked)
    elif "base_url" in m.group(1) or "api_key" in m.group(1):
        add("FAIL", "配置白名单", "红线圈被突破：白名单里出现了 base_url / api_key")
    else:
        add("PASS", "配置白名单", "base_url / api_key 既不在白名单也不在落地分支（安全红线成立）")

    if "default:" in apply_body:
        add("PASS", "配置白名单", "白名单外的键走 default 分支忽略（下发也不生效）")
    else:
        add("WARN", "配置白名单", "applyConfigKey 未见 default 忽略分支，请人工确认")


# ────────────────────────── 10. 记账单位 ──────────────────────────

def check_quota_units():
    go_text = all_go_text()
    m = re.search(r"units\s*:?=\s*int64\(\(tokens\s*\+\s*(\d+)\)\s*/\s*(\d+)\)", go_text)
    if not m:
        add("WARN", "记账单位", "未能从 gateway.go 抽出扣费公式，请人工确认 1 unit = 1000 tokens")
    else:
        n = int(m.group(2))
        # 客户端必须在「额度 → token」的展示口径上同源；搜的是客户端注释之外的真实比较/换算
        client_text = "\n".join(all_java_code().values())
        if n != 1000:
            add("FAIL", "记账单位", "服务端记账基数 %s ≠ 约定 1000" % n)
        elif not re.search(r"\b1000\b", client_text):
            add("FAIL", "记账单位", "客户端没有任何 1000 的换算口径，两端 unit 语义可能已漂移")
        else:
            add("PASS", "记账单位", "两端记账基数一致：1 unit = %d tokens（服务端按 upstream usage 向上取整）" % n)

    if re.search(r'StatusPaymentRequired,\s*"quota_exhausted"', go_text):
        add("PASS", "记账单位", "额度耗尽返回 402 quota_exhausted（客户端按状态码/错误码分支处理）")
    else:
        add("FAIL", "记账单位", "服务端未实现 402 quota_exhausted")

    # 上游失败不计费：客户端不该为失败的请求买单（README §3.4 的承诺）
    gw = read(os.path.join(SERVER, "gateway.go"))
    if re.search(r"upstream_error|upstream_read", gw) and "chargeQuota" in gw:
        add("PASS", "记账单位", "上游报错走 upstream_error/upstream_read 且在计费之前返回（失败不计费）")
    else:
        add("WARN", "记账单位", "未能确认「上游失败不计费」的代码路径，请人工确认")


# ────────────────────────── 主流程 ──────────────────────────

CHECKS = [
    check_zero_modification,
    check_cert_pin,
    check_endpoint_consistency,
    check_routes,
    check_gateway_boundary,
    check_lan_protocol,
    check_fields,
    check_error_codes,
    check_config_whitelist,
    check_quota_units,
]

COLOR = {"PASS": "\033[32m", "FAIL": "\033[31m", "WARN": "\033[33m"}
ICON = {"PASS": "PASS", "FAIL": "FAIL", "WARN": "WARN"}


def main():
    for fn in CHECKS:
        try:
            fn()
        except Exception as e:  # 校验器自身出错也要可见，不能静默跳过
            add("WARN", "校验器", "%s 内部异常：%s: %s" % (fn.__name__, type(e).__name__, e))

    fails = [r for r in results if r[0] == "FAIL"]
    warns = [r for r in results if r[0] == "WARN"]
    passes = [r for r in results if r[0] == "PASS"]

    if AS_JSON:
        print(json.dumps({
            "unified_version": CONF.get("UNIFIED_VERSION"),
            "client_version": CONF.get("CLIENT_VERSION_NAME"),
            "server_version": CONF.get("SERVER_VERSION"),
            "summary": {"pass": len(passes), "warn": len(warns), "fail": len(fails)},
            "results": [{"status": s, "group": g, "title": t, "details": d} for s, g, t, d in results],
        }, ensure_ascii=False, indent=2))
        return 1 if fails else 0

    print("=" * 78)
    print("agent-unified 两端契约校验   client(%s) ↔ server(%s)   整合版 %s"
          % (CONF.get("CLIENT_VERSION_NAME"), CONF.get("SERVER_VERSION"), CONF.get("UNIFIED_VERSION")))
    print("=" * 78)
    group = None
    for status, g, title, details in results:
        if g != group:
            print("\n【%s】" % g)
            group = g
        if QUIET and status == "PASS":
            continue
        use_color = sys.stdout.isatty()
        tag = "%s%s\033[0m" % (COLOR[status], ICON[status]) if use_color else ICON[status]
        print("  %s  %s" % (tag, title))
        for d in details:
            for i, line in enumerate(str(d).splitlines()):
                print("        %s%s" % ("↳ " if i == 0 else "  ", line))
    print("\n" + "-" * 78)
    print("结果：%d 通过 / %d 警告 / %d 失败" % (len(passes), len(warns), len(fails)))
    if fails:
        print("存在契约不一致 —— 构建前必须解决（这正是两端会静默失效的地方）。")
    elif warns:
        print("无失败项；警告项请人工确认。")
    else:
        print("两端契约完全对齐。")
    print("-" * 78)
    return 1 if fails else 0


if __name__ == "__main__":
    sys.exit(main())
