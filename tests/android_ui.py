#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""
Android UI 自动化小工具 —— 用来驱动 AgentLite 完成登录等需要真实 UI 的操作。

为什么需要它：局域网对称密钥是在**登录那一刻**由账号密钥派生的
（`LanSync.deriveAndStoreKey`），所以验证 P2P 链路必须真的走一遍登录页，
不能靠直接往 SharedPreferences 里塞 session 绕过——那样密钥永远不会派生。

只用 adb + uiautomator，不依赖第三方库。
"""
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

def _resolve_adb():
    """自己找 adb —— 不依赖调用方的 PATH（每次 shell 都是新的）。"""
    import os
    import shutil
    found = shutil.which("adb")
    if found:
        return found
    for c in (
        os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
        "/opt/homebrew/bin/adb",
        "/usr/local/bin/adb",
    ):
        if os.path.exists(c):
            return c
    return "adb"


ADB = _resolve_adb()


def sh(*args, timeout=30):
    return subprocess.run([ADB] + list(args), capture_output=True,
                          text=True, timeout=timeout).stdout


def dump(serial, retries=3):
    """导出当前界面层级，返回 (node_list)。"""
    for _ in range(retries):
        sh("-s", serial, "shell", "uiautomator", "dump", "/sdcard/ui.xml")
        out = sh("-s", serial, "shell", "cat", "/sdcard/ui.xml")
        m = re.search(r"<hierarchy.*</hierarchy>", out, re.S)
        if m:
            root = ET.fromstring(m.group(0))
            return list(root.iter())
        time.sleep(0.6)
    return []


def attr(node, name):
    return node.attrib.get(name, "")


def find(nodes, res_id=None, text=None, contains=None):
    for n in nodes:
        if res_id and attr(n, "resource-id").endswith(res_id):
            return n
        if text and attr(n, "text") == text:
            return n
        if contains and contains in attr(n, "text"):
            return n
    return None


def center(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", attr(node, "bounds"))
    if not m:
        raise ValueError("无法解析 bounds: " + attr(node, "bounds"))
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap(serial, node):
    x, y = center(node)
    sh("-s", serial, "shell", "input", "tap", str(x), str(y))
    time.sleep(0.4)


def type_text(serial, node, text):
    """点入输入框 → **先清空** → 再输入。

    必须先清空：`input text` 是追加重放，而登录页会用
    `etName.setText(prefs.accountName())` 预填上一次的账号名，
    直接输入会拼成「旧账号名+新账号名」这种必然登录失败的字符串。
    这个坑实测踩过，表现为服务端返回 401「账号名或账号密钥错误」。
    """
    tap(serial, node)
    sh("-s", serial, "shell", "input", "keyevent", "123")        # MOVE_END
    sh("-s", serial, "shell", "input", "keyevent", *(["67"] * 80))  # DEL ×80
    time.sleep(0.2)
    # input text 不支持非 ASCII，账号名与密钥都是 ASCII，够用
    sh("-s", serial, "shell", "input", "text", text.replace(" ", "%s"))
    time.sleep(0.3)


def launch(serial, component):
    """启动指定 Activity。

    必须带 -S（先 force-stop 目标 App）：否则当目标 Activity 已在任务栈里时，
    am start 会被判定为「intent has been delivered to currently running top-most
    instance」而**静默忽略**，前台仍是上一个页面——实测踩过这个坑。
    """
    return sh("-s", serial, "shell", "am", "start", "-S", "-n", component, timeout=25)


def wait_for(serial, res_id=None, contains=None, timeout=15):
    end = time.time() + timeout
    while time.time() < end:
        nodes = dump(serial)
        n = find(nodes, res_id=res_id, contains=contains)
        if n is not None:
            return n, nodes
        time.sleep(0.8)
    return None, []


def crashes(serial):
    return sh("-s", serial, "logcat", "-d", "-s", "AndroidRuntime:E")


def main():
    """用法: android_ui.py <serial> login <name> <key>"""
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    serial, cmd = sys.argv[1], sys.argv[2]

    if cmd != "login":
        print("未知命令:", cmd)
        sys.exit(1)

    name, key = sys.argv[3], sys.argv[4]
    print("→ 启动登录页")
    out = launch(serial, "com.dsh.agentlite/.LoginActivity")
    for line in out.strip().splitlines():
        print("  " + line.strip())
    if "Warning: Activity not started" in out:
        print("✗ 启动被忽略（intent 被判为重复投递）")
        sys.exit(4)

    node, nodes = wait_for(serial, res_id="etName", timeout=12)
    if node is None:
        print("✗ 找不到账号名输入框 etName —— 登录页没打开或布局变了")
        for n in nodes[:25]:
            rid = attr(n, "resource-id")
            if rid:
                print("    可见控件:", rid)
        sys.exit(2)
    print("✓ 登录页已打开")

    type_text(serial, node, name)
    k = find(nodes, res_id="etKey")
    if k is None:
        print("✗ 找不到密钥输入框 etKey")
        sys.exit(2)
    type_text(serial, k, key)
    print("✓ 已填入账号与密钥")

    btn = find(dump(serial), res_id="btnLogin")
    if btn is None:
        print("✗ 找不到登录按钮 btnLogin")
        sys.exit(2)
    tap(serial, btn)
    print("→ 已点击登录，等待结果")

    time.sleep(3.5)
    nodes = dump(serial)
    msg = find(nodes, res_id="tvMsg")
    if msg is not None and attr(msg, "text"):
        print("  界面提示:", attr(msg, "text"))
    cur = find(nodes, res_id="tvCurrent")
    if cur is not None and attr(cur, "text"):
        print("  当前会话:", attr(cur, "text"))

    err = crashes(serial)
    if "FATAL" in err:
        print("✗ 检测到崩溃:")
        print("\n".join(err.strip().splitlines()[-12:]))
        sys.exit(3)
    print("✓ 无崩溃栈")


if __name__ == "__main__":
    main()
