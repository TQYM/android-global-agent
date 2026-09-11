#!/usr/bin/env python3
"""fake_hdc — 无真机/模拟器时验证 harmony_agent 全链路的模拟 hdc 设备。

原理：本脚本冒充 `hdc` 可执行文件（HDC 环境变量指向它），按 ArkXTest 真实输出格式
应答 dumpLayout / uiInput / snapshot 等命令，并维护一个三屏交互场景（桌面→设置→蓝牙）。

用法（端到端 mock 测试）：
  export HDC="$PWD/tools/harmony-agent/fake_hdc.py"
  python3 tools/harmony-agent/harmony_agent.py --task "打开设置里的蓝牙并开启" --serial FAKE01

场景：桌面(点设置) → 设置列表(点蓝牙) → 蓝牙页(点开关→状态变"已开启"，模型应 done)
状态文件 /tmp/fake_hdc_state.json（rm 即重置）
"""
import json
import os
import re
import sys

STATE = "/tmp/fake_hdc_state.json"
DEV_ROOT = "/tmp/fake_hdc_dev"      # 模拟设备端文件系统（/data/local/tmp/... 映射到本地）
SERIAL = "FAKE01"
SCREEN_W, SCREEN_H = 1260, 2720


def dev_path(p):
    """设备路径 → 本地映射路径（模拟设备端 /data/local/tmp 文件系统）。"""
    os.makedirs(DEV_ROOT, exist_ok=True)
    return os.path.join(DEV_ROOT, p.lstrip("/").replace("/", "_"))


def _attr(**kw):
    base = {
        "bounds": "[0,0][0,0]", "checkable": "false", "checked": "false", "clickable": "false",
        "content-desc": "", "enabled": "true", "focusable": "false", "focused": "false",
        "hashcode": "", "id": "", "key": "", "long-clickable": "false", "package": "",
        "password": "false", "scrollable": "false", "selected": "false", "text": "",
        "type": "text",
    }
    base.update(kw)
    return {"attributes": base, "children": []}


def _page_root(pkg, children):
    root = _attr(type="root", package=pkg, bounds=f"[0,0][{SCREEN_W},{SCREEN_H}]")
    root["children"] = children
    return [root]


def scenario():
    """场景选择：FAKE_SCENARIO=bt(默认) | text_input | not_found | swipe_needed"""
    return os.environ.get("FAKE_SCENARIO", "bt")


def screen_search(text_entered=False, opened=False):
    """text_input 场景：必须先输入文字（验证 uiInput text 路径），点结果后进入蓝牙页"""
    if opened:
        return _page_root("com.huawei.hmos.settings", [
            _attr(type="text", text="蓝牙", bounds="[80,80][400,180]"),
            _attr(type="toggle", text="蓝牙已开启", clickable="true",
                  bounds="[900,300][1160,420]", id="bt_switch"),
        ])
    return _page_root("com.huawei.hmos.settings", [
        _attr(type="text", text="搜索设置项", bounds="[80,80][600,180]"),
        _attr(type="text-input", text="大模型" if text_entered else "", clickable="true",
              bounds="[100,300][1160,440]", id="search_box"),
        _attr(type="list-item", text="搜索结果：蓝牙" if text_entered else "请先输入关键词",
              clickable="true" if text_entered else "false",
              bounds="[0,600][1260,760]", id="search_result"),
    ])


def screen_hidden_target(swiped=False, opened=False):
    """swipe_needed 场景：目标在屏幕外，滑动后可见，点击后进入应用页"""
    if opened:
        return _page_root("com.example.targetapp", [
            _attr(type="text", text="目标应用", bounds="[80,80][600,180]"),
            _attr(type="text", text="已打开", bounds="[80,300][600,400]"),
        ])
    children = [_attr(type="text", text="应用列表", bounds="[80,80][600,180]")]
    if swiped:
        children.append(_attr(type="button", text="目标应用", clickable="true",
                              bounds="[430,1200][830,1600]", id="target_app"))
    else:
        children.append(_attr(type="text", text="（向下滑动查看更多）", bounds="[80,2400][1000,2500]"))
    return _page_root("com.huawei.hmos.launcher", children)


def screen_not_found():
    """not_found 场景：目标不存在（所有点击无反应，验证模型 fail 归因）"""
    return _page_root("com.huawei.hmos.launcher", [
        _attr(type="text", text="桌面", bounds="[80,80][600,180]"),
        _attr(type="button", text="电话", clickable="true", bounds="[180,980][430,1380]"),
        _attr(type="button", text="相机", clickable="true", bounds="[430,980][830,1380]"),
    ])


def screen_home():
    return _page_root("com.huawei.hmos.launcher", [
        _attr(type="text", text="10:24", bounds="[560,40][700,120]"),
        _attr(type="button", text="设置", clickable="true", bounds="[430,980][830,1380]",
              id="launcher_settings", package="com.huawei.hmos.launcher"),
        _attr(type="button", text="相机", clickable="true", bounds="[430,1500][830,1900]"),
        _attr(type="button", text="电话", clickable="true", bounds="[180,980][430,1380]"),
    ])


def screen_settings():
    return _page_root("com.huawei.hmos.settings", [
        _attr(type="text", text="设置", bounds="[80,80][400,180]"),
        _attr(type="list-item", text="WLAN", clickable="true", bounds="[0,560][1260,720]",
              id="settings_wlan"),
        _attr(type="list-item", text="蓝牙", clickable="true", bounds="[0,740][1260,900]",
              id="settings_bluetooth"),
        _attr(type="list-item", text="显示和亮度", clickable="true", bounds="[0,920][1260,1080]"),
    ])


def screen_bt_on(on):
    return _page_root("com.huawei.hmos.settings", [
        _attr(type="text", text="蓝牙", bounds="[80,80][400,180]"),
        _attr(type="toggle", text="蓝牙已开启" if on else "蓝牙已关闭", clickable="true",
              bounds="[900,300][1160,420]", id="bt_switch", checked="true" if on else "false"),
        _attr(type="text", text="已配对的设备" if on else "可用设备", bounds="[80,1400][600,1500]"),
    ])


def current_screen(st):
    sc = scenario()
    if sc == "text_input":
        return screen_search(st.get("text_entered", False), st.get("opened", False))
    if sc == "swipe_needed":
        return screen_hidden_target(st.get("swiped", False), st.get("opened", False))
    if sc == "not_found":
        return screen_not_found()
    stage = st.get("stage", 0)
    if stage == 0:
        return screen_home()
    if stage == 1:
        return screen_settings()
    return screen_bt_on(st.get("bt_on", False))


def load():
    try:
        with open(STATE) as f:
            return json.load(f)
    except Exception:
        return {"stage": 0, "bt_on": False, "log": []}


def save(st):
    with open(STATE, "w") as f:
        json.dump(st, f)


def in_rect(x, y, bounds):
    m = re.match(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", bounds)
    if not m:
        return False
    x1, y1, x2, y2 = map(int, m.groups())
    return x1 <= x <= x2 and y1 <= y <= y2


def handle_click(st, x, y):
    sc = scenario()
    st.setdefault("log", []).append(f"click({x},{y})@[{sc}]")
    if sc == "text_input":
        if st.get("text_entered") and in_rect(x, y, "[0,600][1260,760]"):
            st["opened"] = True
        save(st)
        return
    if sc == "swipe_needed":
        if st.get("swiped") and in_rect(x, y, "[430,1200][830,1600]"):
            st["opened"] = True
        save(st)
        return
    if sc == "not_found":
        save(st)      # 任何点击都无反应
        return
    stage = st.get("stage", 0)
    st.setdefault("log", []).append(f"click({x},{y})@stage{stage}")
    if stage == 0 and in_rect(x, y, "[430,980][830,1380]"):
        st["stage"] = 1
    elif stage == 1 and in_rect(x, y, "[0,740][1260,900]"):
        st["stage"] = 2
        st["bt_on"] = False
    elif stage == 2 and in_rect(x, y, "[900,300][1160,420]"):
        st["bt_on"] = not st.get("bt_on", False)
    save(st)


def shell_cmd(st, cmd):
    """返回 (stdout, exit_code)。"""
    # dumpLayout -p <path>
    m = re.match(r"uitest dumpLayout -p (\S+)", cmd)
    if m:
        path = dev_path(m.group(1))
        with open(path, "w") as f:            # 模拟设备端落盘
            json.dump(current_screen(st), f, ensure_ascii=False)
        save(st)
        return "DumpLayout successful", 0
    m = re.match(r"cat (\S+)", cmd)
    if m:
        try:
            with open(dev_path(m.group(1))) as f:
                return f.read(), 0
        except OSError as e:
            return str(e), 1
    m = re.match(r"uitest uiInput click (-?\d+) (-?\d+)", cmd)
    if m:
        handle_click(st, int(m.group(1)), int(m.group(2)))
        return "click successful", 0
    if cmd.startswith("uitest uiInput swipe"):
        st.setdefault("log", []).append(cmd)
        if scenario() == "swipe_needed":
            st["swiped"] = True
        save(st)
        return "swipe successful", 0
    if cmd.startswith("uitest uiInput text"):
        txt = cmd[len("uitest uiInput text"):].strip()
        st.setdefault("log", []).append(f"text:{txt}")
        # 真实失败语义：中文输入失败时设备状态不发生任何变化
        if any(ord(c) > 127 for c in txt):
            save(st)
            return "error: text input failed (unsupported character)", 1   # 复现中文输入坑
        if scenario() == "text_input" and txt:
            st["text_entered"] = True
        save(st)
        return "text successful", 0
    if cmd.startswith("uitest uiInput keyEvent"):
        st.setdefault("log", []).append(cmd)
        save(st)
        return "keyEvent successful", 0
    if cmd.startswith("snapshot_display") or cmd.startswith("snapshot"):
        m = re.search(r"-f (\S+)", cmd)
        if m:
            with open(dev_path(m.group(1)), "wb") as f:   # 1x1 PNG 占位
                f.write(bytes.fromhex(
                    "89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4"
                    "890000000a49444154789c6300010000050001"
                    "0d0a2db40000000049454e44ae426082"))
        return "snapshot successful", 0
    if cmd.startswith("hidumper"):
        return f"{SCREEN_W}x{SCREEN_H}", 0
    if cmd.startswith("aa start"):
        st.setdefault("log", []).append(cmd)
        save(st)
        return "start ability successfully", 0
    return "", 0


def main():
    argv = sys.argv[1:]
    st = load()
    # 去掉 -t SERIAL
    if argv[:1] == ["-t"]:
        argv = argv[2:]
    if argv[:1] == ["list"]:
        print("FAKE01")
        return 0
    if argv[:1] == ["shell"]:
        out, rc = shell_cmd(st, " ".join(argv[1:]))
        print(out)
        return rc
    if argv[:1] == ["file"] and len(argv) >= 4 and argv[1] == "recv":
        try:
            with open(dev_path(argv[2]), "rb") as src, open(argv[3], "wb") as dst:
                dst.write(src.read())
            print("FileTransfer finish")
            return 0
        except OSError as e:
            print(f"error: {e}", file=sys.stderr)
            return 1
    if argv[:1] == ["version"]:
        print("HarmonyOS Device Connector (hdc) ver:2.0.0-fake")
        return 0
    return 0


if __name__ == "__main__":
    sys.exit(main())
