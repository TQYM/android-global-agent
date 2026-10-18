#!/usr/bin/env python3
"""在数据副本上演练服务端首次迁移、重开幂等与回滚边界。

对应实施报告 docs/INTERFACE_IMPLEMENTATION_2026-10-11.zh-CN.md 第 4 节的
「上线前最小操作顺序」：用**真实二进制 + 真实 TLS + 真实 HTTP**在隔离副本上
验证旧 JSON 到单日志事实源的迁移，而不是只跑结构层单测。

安全性：全程只读写隔离临时目录，不触碰 /var/lib/agentlite-server，
不安装、不部署、不提交，不改动仓库内任何文件。

用法：
    python3 tests/migration_drill.py            # 跑完自动清理
    python3 tests/migration_drill.py --keep      # 保留工作目录并打印路径
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import re
import shutil
import socket
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SERVER_DIR = ROOT / "agentlite-server"

# 令牌明文 → sha256 十六进制即为旧版 TokenHash
LEGACY_TOKEN_ALICE = "legacy-token-alice-2026"
LEGACY_TOKEN_BOB = "legacy-token-bob-2026"
ALICE_KEY = "AliceKey-Str0ng-2026"
BOB_KEY = "BobKey-Str0ng-2026"

_results: list[tuple[bool, str, str]] = []


def check(name: str, ok: bool, detail: str = "") -> bool:
    _results.append((bool(ok), name, detail))
    mark = "PASS" if ok else "FAIL"
    line = f"  [{mark}] {name}"
    if detail:
        line += f" — {detail}"
    print(line, flush=True)
    return bool(ok)


def section(title: str) -> None:
    print(f"\n== {title} ==", flush=True)


def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def token_hash(token: str) -> str:
    return sha256_hex(token.encode("utf-8"))


def read_source_constant(name: str, path: Path) -> int:
    """从生产源码读取常量，避免演练脚本与实现漂移。"""
    text = path.read_text(encoding="utf-8")
    match = re.search(rf"^\s*{name}\s*=\s*([0-9_]+)", text, re.M)
    if not match:
        raise SystemExit(f"无法从 {path} 读取常量 {name}")
    return int(match.group(1).replace("_", ""))


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def make_certificate(work: Path) -> tuple[Path, Path]:
    conf = work / "openssl.cnf"
    conf.write_text(
        "[req]\n"
        "distinguished_name=dn\n"
        "x509_extensions=v3\n"
        "prompt=no\n"
        "[dn]\n"
        "CN=127.0.0.1\n"
        "[v3]\n"
        "subjectAltName=IP:127.0.0.1,DNS:localhost\n"
        "basicConstraints=CA:TRUE\n",
        encoding="utf-8",
    )
    crt, key = work / "server.crt", work / "server.key"
    subprocess.run(
        [
            "openssl", "req", "-x509", "-newkey", "rsa:2048",
            "-keyout", str(key), "-out", str(crt), "-days", "2", "-nodes",
            "-config", str(conf),
        ],
        check=True,
        capture_output=True,
    )
    return crt, key


_services: list["Service"] = []


class Service:
    """真实二进制进程；启动后轮询 /health，退出保证回收。"""

    def __init__(self, binary: Path, data: Path, crt: Path, key: Path, port: int, log: Path):
        self.binary, self.data, self.crt, self.key = binary, data, crt, key
        self.port, self.log_path = port, log
        self.proc: subprocess.Popen | None = None
        self.log_file = None
        self.log_offset = 0
        self.ctx = ssl.create_default_context()
        self.ctx.check_hostname = False
        self.ctx.verify_mode = ssl.CERT_NONE
        _services.append(self)

    @property
    def base(self) -> str:
        return f"https://127.0.0.1:{self.port}"

    def start(self, wait: float = 25.0) -> bool:
        # 只统计本次启动写入的日志，避免上一次启动的错误串到本次断言
        self.log_offset = self.log_path.stat().st_size if self.log_path.exists() else 0
        self.log_file = open(self.log_path, "ab")
        self.proc = subprocess.Popen(
            [
                str(self.binary), "-data", str(self.data),
                "-addr", f"127.0.0.1:{self.port}",
                "-cert", str(self.crt), "-key", str(self.key),
            ],
            stdout=self.log_file, stderr=subprocess.STDOUT,
        )
        deadline = time.time() + wait
        while time.time() < deadline:
            if self.proc.poll() is not None:
                return False
            try:
                status, _ = self.request("GET", "/health")
                if status == 200:
                    return True
            except Exception:
                time.sleep(0.15)
        return False

    def stop(self) -> int:
        if not self.proc:
            return 0
        self.proc.terminate()
        try:
            code = self.proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self.proc.kill()
            code = self.proc.wait(timeout=5)
        if self.log_file:
            self.log_file.close()
            self.log_file = None
        self.proc = None
        return code

    def request(self, method: str, path: str, token: str | None = None,
                device: str | None = None, body: dict | None = None):
        data = None if body is None else json.dumps(body).encode("utf-8")
        req = urllib.request.Request(self.base + path, data=data, method=method)
        if data is not None:
            req.add_header("Content-Type", "application/json")
        if token:
            req.add_header("Authorization", f"Bearer {token}")
        if device:
            req.add_header("X-Device-Id", device)
        try:
            with urllib.request.urlopen(req, context=self.ctx, timeout=15) as resp:
                raw = resp.read()
                status = resp.status
        except urllib.error.HTTPError as err:
            raw = err.read()
            status = err.code
        try:
            return status, json.loads(raw.decode("utf-8"))
        except Exception:
            return status, raw.decode("utf-8", "replace")

    def log_text(self) -> str:
        """本次启动以来的日志（跨启动累积会让损坏边界断言假通过）。"""
        if not self.log_path.exists():
            return ""
        with open(self.log_path, "rb") as fh:
            fh.seek(self.log_offset)
            return fh.read().decode("utf-8", "replace")


def account_blob(account_id: str, name: str, key: str, quota: int, used: int,
                 token: str, salt: bytes, iterations: int, key_len: int) -> dict:
    derived = hashlib.pbkdf2_hmac("sha256", key.encode("utf-8"), salt, iterations, key_len)
    return {
        "account_id": account_id,
        "name": name,
        "key_hash": base64.b64encode(derived).decode(),
        "key_salt": base64.b64encode(salt).decode(),
        "key_iter": iterations,
        "token_hash": token_hash(token),
        "created_at": "2026-08-01T10:00:00Z",
        "last_login_at": "2026-10-08T21:30:00Z",
        "quota": quota,
        "quota_used": used,
    }


def count_jsonl(path: Path) -> int:
    if not path.exists():
        return 0
    text = path.read_text(encoding="utf-8")
    return sum(1 for line in text.splitlines() if line.strip())


def run_race(*workers) -> None:
    """并发执行若干 worker，制造真实竞争窗口（阶段 J 用）。

    每个 worker 内部先 `Barrier.wait()` 再发请求，保证是「同时进入临界区」的真并发，
    而不是「先启动的线程已经跑完」的假并发。worker 数量必须与调用方 Barrier 的
    parties 一致，否则会死锁。
    """
    threads = [threading.Thread(target=w) for w in workers]
    for t in threads:
        t.start()
    for t in threads:
        t.join()


def build_legacy_tree(data: Path, iterations: int, key_len: int) -> dict[str, bytes]:
    """构造等价生产旧形态的数据副本，返回各旧文件的原始字节用于后续比对。"""
    (data / "records").mkdir(parents=True, exist_ok=True)
    originals: dict[str, bytes] = {}

    accounts = [
        account_blob("a_legacy1", "alice", ALICE_KEY, 9000, 1500,
                     LEGACY_TOKEN_ALICE, b"0123456789abcdef", iterations, key_len),
        account_blob("a_legacy2", "bob", BOB_KEY, 300, 300,
                     LEGACY_TOKEN_BOB, b"fedcba9876543210", iterations, key_len),
    ]
    codes = [
        {"code": "LEGACY-UNUSED", "units": 500, "batch": "b1",
         "created_at": "2026-09-01T00:00:00Z"},
        {"code": "LEGACY-USED", "units": 100, "batch": "b1",
         "created_at": "2026-09-01T00:00:00Z",
         "used_by": "a_legacy1", "used_at": "2026-09-02T00:00:00Z"},
        # 阶段 J（§4.2 支付竞争 smoke）用：三张额度互不相同的未用码，
        # 使「谁赢了就加谁那份额度」可以被精确断言，而不只是看有没有加。
        {"code": "LEGACY-PAY1", "units": 500, "batch": "b2",
         "created_at": "2026-10-01T00:00:00Z"},
        {"code": "LEGACY-PAY2", "units": 700, "batch": "b2",
         "created_at": "2026-10-01T00:00:00Z"},
        {"code": "LEGACY-PAY3", "units": 900, "batch": "b2",
         "created_at": "2026-10-01T00:00:00Z"},
    ]
    orders = [
        {"order_id": "o_pending", "account_id": "a_legacy1", "pack_id": "p1",
         "pack_name": "试用包", "units": 0, "price_cny": 6.0, "status": "pending",
         "created_at": "2026-10-01T00:00:00Z", "expires_at": "2026-10-02T00:00:00Z"},
        {"order_id": "o_paid", "account_id": "a_legacy2", "pack_id": "p2",
         "pack_name": "标准包", "units": 300, "price_cny": 30.0, "status": "paid",
         "created_at": "2026-09-20T00:00:00Z", "paid_at": "2026-09-20T00:05:00Z",
         "expires_at": "2026-10-20T00:00:00Z"},
    ]
    devices = [
        {"device_id": "d_old_a", "account_id": "a_legacy1", "fingerprint": "fp-alice-1",
         "name": "Alice Phone", "model": "PKX110", "brand": "OPPO", "sdk": 36,
         "app_version": "0.6.0", "created_at": "2026-08-02T00:00:00Z",
         "last_seen_at": "2026-10-08T20:00:00Z",
         "last_status": {"battery": 71, "screen": "on"}},
        {"device_id": "d_old_orphan", "fingerprint": "fp-orphan", "name": "未归属",
         "created_at": "2026-08-03T00:00:00Z"},
    ]

    for name, blob in (
        ("accounts.json", accounts),
        ("redeem_codes.json", codes),
        ("orders.json", orders),
        ("devices.json", devices),
    ):
        raw = json.dumps(blob, ensure_ascii=False).encode("utf-8")
        (data / name).write_bytes(raw)
        originals[name] = raw

    # 账号记录流（新形态已是事实源）
    alice_records = "".join(
        json.dumps({"seq": i, "kind": "task", "id": f"t_alice_{i}",
                    "device_id": "d_old_a", "data": {"n": i}}, ensure_ascii=False) + "\n"
        for i in (1, 2, 3)
    )
    (data / "records" / "a_legacy1.jsonl").write_text(alice_records, encoding="utf-8")
    originals["records/a_legacy1.jsonl"] = alice_records.encode("utf-8")

    # 旧版「按设备归档」的记录：首次绑定时应迁移进账号流
    orphan_records = "".join(
        json.dumps({"seq": i, "kind": "task", "id": f"t_orphan_{i}",
                    "data": {"n": i}}, ensure_ascii=False) + "\n"
        for i in (1, 2)
    )
    (data / "records" / "d_old_orphan.jsonl").write_text(orphan_records, encoding="utf-8")
    originals["records/d_old_orphan.jsonl"] = orphan_records.encode("utf-8")

    # 旧额度流水归档：存在但不应被当作入账依据重复计费
    ledger = json.dumps({"reason": "legacy", "delta": -50, "at": "2026-09-10T00:00:00Z"}) + "\n"
    (data / "quota_ledger.json").write_text(ledger, encoding="utf-8")
    originals["quota_ledger.json"] = ledger.encode("utf-8")

    return originals


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--keep", action="store_true", help="保留工作目录")
    parser.add_argument("--port", type=int, default=0)
    args = parser.parse_args()

    iterations = read_source_constant("acctKeyIterations", SERVER_DIR / "account.go")
    key_len = read_source_constant("acctKeyLen", SERVER_DIR / "account.go")

    work = Path(tempfile.mkdtemp(prefix="agentlite-migration-drill-"))
    print(f"演练工作目录：{work}", flush=True)
    print(f"派生参数：iterations={iterations} key_len={key_len}（读自 account.go）", flush=True)

    try:
        section("A. 构建真实服务端二进制")
        binary = work / "agentlite-server"
        build = subprocess.run(
            ["go", "build", "-o", str(binary), "."],
            cwd=SERVER_DIR, capture_output=True, text=True,
        )
        if not check("go build 成功", build.returncode == 0, build.stderr.strip()[:200]):
            return 1
        check("二进制存在且可执行", binary.is_file() and os.access(binary, os.X_OK),
              f"{binary.stat().st_size} 字节")

        crt, key = make_certificate(work)
        port = args.port or free_port()
        main_dir = work / "data"
        main_dir.mkdir()
        originals = build_legacy_tree(main_dir, iterations, key_len)
        check("构造旧形态数据副本", len(originals) == 7,
              f"accounts/redeem_codes/orders/devices/quota_ledger + 2 份记录流，共 {len(originals)} 份")

        svc = Service(binary, main_dir, crt, key, port, work / "server.log")

        # ---------- 首次迁移 ----------
        section("B. 首次启动：旧 JSON → 单日志事实源")
        if not check("服务启动并通过 /health", svc.start(), "旧数据存在时应正常迁移启动"):
            print(svc.log_text()[-2000:], flush=True)
            return 1
        check("首次迁移产出 billing.jsonl", (main_dir / "billing.jsonl").is_file())
        check("首次迁移产出 devices.jsonl", (main_dir / "devices.jsonl").is_file())

        section("C. HTTP 核对：旧令牌、额度、记录与分页")
        status, me_alice = svc.request("GET", "/api/v1/account/me", token=LEGACY_TOKEN_ALICE)
        check("旧版单令牌仍可作为 Bearer 使用", status == 200 and me_alice.get("account_id") == "a_legacy1",
              f"status={status} account={me_alice.get('account_id') if isinstance(me_alice, dict) else me_alice}")
        check("旧账号额度与已用量保持", isinstance(me_alice, dict) and me_alice.get("quota") == 9000
              and me_alice.get("quota_used") == 1500,
              f"quota={me_alice.get('quota') if isinstance(me_alice, dict) else '?'} "
              f"used={me_alice.get('quota_used') if isinstance(me_alice, dict) else '?'}")

        status, me_bob = svc.request("GET", "/api/v1/account/me", token=LEGACY_TOKEN_BOB)
        check("第二账号独立迁移", status == 200 and me_bob.get("quota") == 300,
              f"status={status} quota={me_bob.get('quota') if isinstance(me_bob, dict) else me_bob}")

        status, pull1 = svc.request("GET", "/api/v1/sync/pull?since=0&limit=2",
                                    token=LEGACY_TOKEN_ALICE, device="d_old_a")
        ok = (status == 200 and isinstance(pull1, dict) and len(pull1.get("records", [])) == 2
              and pull1.get("next_since") == 2 and pull1.get("server_seq") == 3
              and pull1.get("has_more") is True)
        check("第一页：2 条记录、next_since=2、server_seq=3、has_more", ok,
              f"status={status} next_since={pull1.get('next_since') if isinstance(pull1, dict) else '?'} "
              f"server_seq={pull1.get('server_seq') if isinstance(pull1, dict) else '?'}")

        status, pull2 = svc.request("GET", "/api/v1/sync/pull?since=2&limit=2",
                                    token=LEGACY_TOKEN_ALICE, device="d_old_a")
        ok = (status == 200 and isinstance(pull2, dict) and len(pull2.get("records", [])) == 1
              and pull2.get("next_since") == 3 and pull2.get("has_more") is False)
        check("第二页：收敛且 has_more=False（消费游标不跳页）", ok,
              f"next_since={pull2.get('next_since') if isinstance(pull2, dict) else '?'} "
              f"has_more={pull2.get('has_more') if isinstance(pull2, dict) else '?'}")

        status, orders = svc.request("GET", "/api/v1/orders", token=LEGACY_TOKEN_ALICE)
        ok = status == 200 and isinstance(orders, dict) and any(
            o.get("order_id") == "o_pending" for o in orders.get("orders", []))
        check("旧订单迁移后可查询", ok, f"status={status}")

        svc.stop()

        section("D. 停机核对：日志内容与旧文件归档")
        billing = (main_dir / "billing.jsonl").read_bytes()
        check("billing.jsonl 存在且含完整提交行", billing.count(b"\n") >= 1,
              f"{billing.count(b'\n')} 行")
        first_tx = json.loads(billing.split(b"\n")[0].decode("utf-8"))
        check("初始提交含 2 账号 / 5 兑换码 / 2 订单",
              len(first_tx.get("accounts", [])) == 2 and len(first_tx.get("codes", [])) == 5
              and len(first_tx.get("orders", [])) == 2,
              f"accounts={len(first_tx.get('accounts', []))} codes={len(first_tx.get('codes', []))} "
              f"orders={len(first_tx.get('orders', []))}")
        check("账务版本号为 1", first_tx.get("version") == 1, f"version={first_tx.get('version')}")
        check("旧 TokenHash 已并入 Tokens（写回清空历史字段）",
              all(a.get("token_hash", "") == "" and len(a.get("tokens", [])) == 1
                  for a in first_tx.get("accounts", [])),
              "每账号 1 把迁移令牌")

        devices_lines = count_jsonl(main_dir / "devices.jsonl")
        check("devices.jsonl 覆盖两台旧设备", devices_lines >= 2, f"{devices_lines} 行")

        intact = all((main_dir / name).read_bytes() == raw for name, raw in originals.items()
                     if not name.startswith("records/"))
        check("旧 JSON 原样保留为历史归档（无双写）", intact,
              "accounts/redeem_codes/orders/devices/quota_ledger 字节未变")
        # billing.jsonl 首次导入的账号数组顺序由服务端 map 迭代决定（billing.go:63
        # `for _, a := range s.accounts`），与账号身份无关。断言必须按 id 取，
        # 取下标会随进程内 map 顺序偶发假失败（且旧 detail 写死 "alice" 会掩盖它）。
        by_id = {a.get("account_id"): a for a in first_tx.get("accounts", [])}
        alice_quota = by_id.get("a_legacy1", {}).get("quota")
        check("旧配额流水未作为入账依据重放", alice_quota == 9000,
              f"a_legacy1 额度={alice_quota}（旧 quota_ledger 未被当作入账依据重放）")

        # ---------- 重开幂等 ----------
        section("E. 重开：不重复导入、不重复入账")
        before_sha = sha256_hex(billing)
        if not check("二次启动成功", svc.start(), "旧 JSON 已存在时不应重复导入"):
            print(svc.log_text()[-2000:], flush=True)
            return 1
        check("重开未重写 billing.jsonl", sha256_hex((main_dir / "billing.jsonl").read_bytes()) == before_sha)

        status, me_after = svc.request("GET", "/api/v1/account/me", token=LEGACY_TOKEN_ALICE)
        check("重开后额度未翻倍", status == 200 and me_after.get("quota") == 9000,
              f"quota={me_after.get('quota') if isinstance(me_after, dict) else me_after}")
        status, pull_again = svc.request("GET", "/api/v1/sync/pull?since=0&limit=10",
                                         token=LEGACY_TOKEN_ALICE, device="d_old_a")
        check("重开后记录未翻倍（仍是 3 条）",
              status == 200 and len(pull_again.get("records", [])) == 3,
              f"条数={len(pull_again.get('records', [])) if isinstance(pull_again, dict) else '?'}")

        section("F. 孤儿设备认领与按设备归档记录的迁移")
        status, bob_new = svc.request("POST", "/api/v1/account/login",
                                      body={"name": "bob", "key": BOB_KEY})
        bob_token = bob_new.get("token") if isinstance(bob_new, dict) else None
        check("旧账号可用原密钥登录并签发新令牌", status == 200 and bool(bob_token),
              f"status={status}")

        status, bound = svc.request("POST", "/api/v1/device/bind", token=bob_token,
                                    body={"fingerprint": "fp-orphan", "name": "Bob Phone",
                                          "model": "PKX110", "brand": "OPPO", "sdk": 36,
                                          "app_version": "0.6.0"})
        claimed = (isinstance(bound, dict) and bound.get("device_id") == "d_old_orphan"
                   and bound.get("created") is False)
        check("未归属旧设备被认领而非新建", status == 200 and claimed,
              f"status={status} device_id={bound.get('device_id') if isinstance(bound, dict) else '?'} "
              f"created={bound.get('created') if isinstance(bound, dict) else '?'}")

        status, orphan_pull = svc.request("GET", "/api/v1/sync/pull?since=0&limit=10",
                                          token=bob_token, device="d_old_orphan")
        ids = {r.get("id") for r in orphan_pull.get("records", [])} if isinstance(orphan_pull, dict) else set()
        check("旧按设备归档的记录迁入账号流", status == 200 and {"t_orphan_1", "t_orphan_2"} <= ids,
              f"ids={sorted(ids)}")

        section("G. 迁移后新增写入与旧文件失效")
        status, redeemed = svc.request("POST", "/api/v1/redeem", token=LEGACY_TOKEN_ALICE,
                                       body={"code": "LEGACY-UNUSED"})
        ok = status == 200 and isinstance(redeemed, dict) and redeemed.get("quota") == 9500
        check("旧兑换码可用且只加一次 500", ok,
              f"status={status} quota={redeemed.get('quota') if isinstance(redeemed, dict) else redeemed}")

        status, reused = svc.request("POST", "/api/v1/redeem", token=LEGACY_TOKEN_BOB,
                                     body={"code": "LEGACY-UNUSED"})
        status_after, me_bob_after = svc.request("GET", "/api/v1/account/me", token=LEGACY_TOKEN_BOB)
        check("已用兑换码不可重复使用且不加额度",
              status != 200 and status_after == 200 and me_bob_after.get("quota") == 300,
              f"status={status} err={reused.get('error') if isinstance(reused, dict) else reused} "
              f"bob_quota={me_bob_after.get('quota') if isinstance(me_bob_after, dict) else '?'}")

        status, pushed = svc.request("POST", "/api/v1/sync/push", token=LEGACY_TOKEN_ALICE,
                                     device="d_old_a",
                                     body={"tasks": [{"id": "t_new_1", "ts": "2026-10-11T14:00:00Z",
                                                      "data": {"hello": "world"}}],
                                           "status": {"battery": 66}})
        check("迁移后新记录可写入", status == 200 and isinstance(pushed, dict)
              and pushed.get("accepted") == 1,
              f"status={status} accepted={pushed.get('accepted') if isinstance(pushed, dict) else '?'}")

        status, dup = svc.request("POST", "/api/v1/sync/push", token=LEGACY_TOKEN_ALICE,
                                  device="d_old_a",
                                  body={"tasks": [{"id": "t_new_1", "ts": "2026-10-11T14:00:00Z",
                                                   "data": {"hello": "world"}}]})
        check("同 id 重推按重复处理而非新增", status == 200 and isinstance(dup, dict)
              and dup.get("duplicates") == 1,
              f"duplicates={dup.get('duplicates') if isinstance(dup, dict) else '?'}")

        svc.stop()

        # 迁移后旧 JSON 不再是事实源：故意改坏它，重启状态必须不受影响
        (main_dir / "accounts.json").write_text('{"corrupted":true}', encoding="utf-8")
        (main_dir / "devices.json").write_text('{"corrupted":true}', encoding="utf-8")
        if not check("旧 JSON 改坏后仍能启动（已不是事实源）", svc.start()):
            print(svc.log_text()[-2000:], flush=True)
            return 1
        status, me_final = svc.request("GET", "/api/v1/account/me", token=LEGACY_TOKEN_ALICE)
        check("兑换后的额度跨重启保持 9500", status == 200 and me_final.get("quota") == 9500,
              f"quota={me_final.get('quota') if isinstance(me_final, dict) else me_final}")
        status, pull_final = svc.request("GET", "/api/v1/sync/pull?since=0&limit=20",
                                         token=LEGACY_TOKEN_ALICE, device="d_old_a")
        final_ids = {r.get("id") for r in pull_final.get("records", [])} if isinstance(pull_final, dict) else set()
        check("新记录跨重启保留且 seq 延续",
              status == 200 and "t_new_1" in final_ids and len(final_ids) == 4,
              f"ids={sorted(final_ids)}")
        check("设备归属未被改坏的旧快照回滚",
              isinstance(me_final, dict) and any(d.get("device_id") == "d_old_a"
                                                 for d in me_final.get("devices", [])))
        svc.stop()

        # ---- 阶段 J：§4.2 要求的「支付竞争 smoke」 ----
        # 单元层已有 billing_test.go 的并发用例；这里要在数据副本上、用真实二进制 +
        # 真实 HTTPS 再打一遍，证明迁移后的账务在真实竞争下不会双重记账。
        section("J. 支付竞争 smoke（副本 + 真实二进制 + 真实 TLS 并发）")
        if not check("支付竞争前置：服务可重启", svc.start()):
            print(svc.log_text()[-2000:], flush=True)
            return 1

        status, me_pre = svc.request("GET", "/api/v1/account/me", token=LEGACY_TOKEN_ALICE)
        quota_pre = me_pre.get("quota") if isinstance(me_pre, dict) else None
        check("支付竞争前读到 alice 基准额度", status == 200 and isinstance(quota_pre, int),
              f"status={status} quota={quota_pre}")

        status, created1 = svc.request("POST", "/api/v1/orders", token=LEGACY_TOKEN_ALICE,
                                       body={"pack_id": "starter"})
        order1 = created1.get("order") if isinstance(created1, dict) else None
        oid1 = order1.get("order_id") if isinstance(order1, dict) else None
        check("迁移后账号可创建新订单", status == 200 and bool(oid1),
              f"status={status} order_id={oid1} units={order1.get('units') if isinstance(order1, dict) else '?'}")

        # J1：同一张订单，并发「用码支付」与「取消」——只能有一个赢家
        race1: dict[str, tuple[int, object]] = {}
        gate1 = threading.Barrier(2)

        def _pay1() -> None:
            gate1.wait()
            race1["pay"] = svc.request("POST", "/api/v1/orders/pay", token=LEGACY_TOKEN_ALICE,
                                       body={"order_id": oid1, "code": "LEGACY-PAY1"})

        def _cancel1() -> None:
            gate1.wait()
            race1["cancel"] = svc.request("POST", "/api/v1/orders/cancel", token=LEGACY_TOKEN_ALICE,
                                          body={"order_id": oid1})

        run_race(_pay1, _cancel1)
        pay_won = race1["pay"][0] == 200
        cancel_won = race1["cancel"][0] == 200
        check("并发 pay/cancel 恰好一个赢家", pay_won != cancel_won,
              f"pay={race1['pay'][0]} cancel={race1['cancel'][0]}")

        # J2：另一张订单，两个不同兑换码并发支付——只能消耗其中一个
        status, created2 = svc.request("POST", "/api/v1/orders", token=LEGACY_TOKEN_ALICE,
                                       body={"pack_id": "starter"})
        order2 = created2.get("order") if isinstance(created2, dict) else None
        oid2 = order2.get("order_id") if isinstance(order2, dict) else None
        check("第二张订单创建成功", status == 200 and bool(oid2) and oid2 != oid1,
              f"status={status} order_id={oid2}")

        race2: dict[str, tuple[int, object]] = {}
        gate2 = threading.Barrier(2)

        def _pay2() -> None:
            gate2.wait()
            race2["LEGACY-PAY2"] = svc.request("POST", "/api/v1/orders/pay", token=LEGACY_TOKEN_ALICE,
                                               body={"order_id": oid2, "code": "LEGACY-PAY2"})

        def _pay3() -> None:
            gate2.wait()
            race2["LEGACY-PAY3"] = svc.request("POST", "/api/v1/orders/pay", token=LEGACY_TOKEN_ALICE,
                                               body={"order_id": oid2, "code": "LEGACY-PAY3"})

        run_race(_pay2, _pay3)
        winners = sorted(code for code, res in race2.items() if res[0] == 200)
        check("同一订单两码并发支付只成功一次", len(winners) == 1,
              f"PAY2={race2['LEGACY-PAY2'][0]} PAY3={race2['LEGACY-PAY3'][0]} winners={winners}")

        svc.stop()
        if not check("支付竞争后服务可重开", svc.start()):
            print(svc.log_text()[-2000:], flush=True)
            return 1

        # J3：重开后账务必须与竞争结果严格一致——不多记（双重支付）也不少记
        # （注意：支付加的是「兑换码的 units」，不是套餐的 units，见 quota.go redeemLocked）
        code_units = {"LEGACY-PAY1": 500, "LEGACY-PAY2": 700, "LEGACY-PAY3": 900}
        gained = (code_units["LEGACY-PAY1"] if pay_won else 0) + sum(code_units[c] for c in winners)
        status, me_post = svc.request("GET", "/api/v1/account/me", token=LEGACY_TOKEN_ALICE)
        quota_post = me_post.get("quota") if isinstance(me_post, dict) else None
        check("重开后额度 = 基准 + 恰好赢家份额",
              status == 200 and isinstance(quota_pre, int) and quota_post == quota_pre + gained,
              f"quota={quota_post} 基准={quota_pre} 期望增量={gained}"
              f"（pay_won={pay_won} winners={winners}）")

        status, orders_j = svc.request("GET", "/api/v1/orders", token=LEGACY_TOKEN_ALICE)
        paid_ids = {o.get("order_id") for o in orders_j.get("orders", [])
                    if o.get("status") == "paid"} if isinstance(orders_j, dict) else set()
        expect_paid = ({oid2} if winners else set()) | ({oid1} if pay_won else set())
        check("订单 paid 集合恰为赢家集合（无幽灵支付）", paid_ids == expect_paid,
              f"paid={sorted(paid_ids)} 期望={sorted(expect_paid)}")
        svc.stop()

        section("H. 回滚与损坏边界")
        good_billing = (main_dir / "billing.jsonl").read_bytes()
        # H1: 完整坏行必须是启动错误，不能静默跳过
        (main_dir / "billing.jsonl").write_bytes(good_billing + b'{"version":1,"broken"\n')
        started = svc.start(wait=12.0)
        log = svc.log_text()
        check("完整坏账务行导致启动失败而非静默跳过", not started and "billing.jsonl" in log,
              "启动被拒绝" if not started else "竟然启动了")
        svc.stop()
        (main_dir / "billing.jsonl").write_bytes(good_billing)

        # H2: 未知账务版本同样是启动错误
        bad_version = json.dumps({"version": 99, "accounts": []}).encode() + b"\n"
        (main_dir / "billing.jsonl").write_bytes(good_billing + bad_version)
        started = svc.start(wait=12.0)
        check("未知账务版本导致启动失败", not started and "版本" in svc.log_text(), "版本校验生效")
        svc.stop()
        (main_dir / "billing.jsonl").write_bytes(good_billing)

        # H3: 末尾半行按未提交尾部恢复
        records_path = main_dir / "records" / "a_legacy1.jsonl"
        good_records = records_path.read_bytes()
        records_path.write_bytes(good_records + b'{"seq":9,"kind":"task","id":"half')
        started = svc.start(wait=20.0)
        check("末尾半行可恢复启动", started, "截断到最后一个完整事务")
        if started:
            status, pull_half = svc.request("GET", "/api/v1/sync/pull?since=0&limit=20",
                                            token=LEGACY_TOKEN_ALICE, device="d_old_a")
            ids = {r.get("id") for r in pull_half.get("records", [])} if isinstance(pull_half, dict) else set()
            check("半行未被发布为记录", "half" not in ids and len(ids) == 4, f"ids={sorted(ids)}")
            svc.stop()
        check("半行已被截回原尾部", records_path.read_bytes() == good_records,
              f"{len(records_path.read_bytes())} 字节")

        section("I. 演练结论")
        failed = [name for ok, name, _ in _results if not ok]
        total = len(_results)
        print(f"\n结果：{total - len(failed)} 通过 / {len(failed)} 失败", flush=True)
        for name in failed:
            print(f"  FAIL {name}", flush=True)
        return 0 if not failed else 1
    finally:
        for svc_left in _services:
            try:
                svc_left.stop()
            except Exception:
                pass
        if args.keep:
            print(f"\n工作目录保留：{work}", flush=True)
        else:
            shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
