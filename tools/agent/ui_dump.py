#!/usr/bin/env python3
"""Semantic UI bridge for Android devices (standard library only).

Turns `adb shell uiautomator dump` into a compact list of interactive
nodes (text / content-desc / resource-id / class / bounds / tap center)
that any LLM agent — DSH, Claude, a plain script — can reason about.
This replaces coordinate-only "pure vision" automation with semantics,
keeping a screenshot command as the optional multimodal fallback.

Usage:
    python ui_dump.py dump                 # Markdown table of nodes
    python ui_dump.py dump --json          # machine-readable
    python ui_dump.py dump --all           # include non-interactive nodes
    python ui_dump.py screen out.png       # PNG screenshot via exec-out
    python ui_dump.py dump --serial DEVICE

Notes:
    * `uiautomator dump` needs no AccessibilityService toggle; it runs
      as an instrumentation command and works over plain adb.
    * On busy screens it can report "could not get idle state"; the
      script retries twice. FLAG_SECURE surfaces still expose semantics
      (the screenshot stays black, by design).
"""

import argparse
import json
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

DUMP_PATH = "/sdcard/window_dump.xml"


def run_adb(argv, serial=None, binary=False):
    cmd = ["adb"]
    if serial:
        cmd += ["-s", serial]
    cmd += argv
    result = subprocess.run(cmd, capture_output=True)
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError("adb failed: " + " ".join(cmd) + " :: " + detail)
    if binary:
        return result.stdout
    return result.stdout.decode("utf-8", errors="replace")


def dump_hierarchy_xml(serial, retries=2):
    last = ""
    for _ in range(retries + 1):
        out = run_adb(["shell", "uiautomator", "dump", DUMP_PATH], serial)
        if "dumped to" in out:
            xml_text = run_adb(["exec-out", "cat", DUMP_PATH], serial,
                               binary=True).decode("utf-8", errors="replace")
            if "<hierarchy" in xml_text:
                return xml_text
            last = "empty hierarchy payload"
        else:
            last = out.strip()
        time.sleep(0.6)
    raise RuntimeError("uiautomator dump failed: " + (last or "no output"))


BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def parse_bounds(value):
    match = BOUNDS_RE.fullmatch(value or "")
    if not match:
        return None, None
    left, top, right, bottom = (int(part) for part in match.groups())
    rect = [left, top, right, bottom]
    center = [(left + right) // 2, (top + bottom) // 2]
    return rect, center


def is_interesting(node):
    return (
        node.get("clickable") == "true"
        or node.get("scrollable") == "true"
        or node.get("checkable") == "true"
        or bool((node.get("text") or "").strip())
        or bool((node.get("content-desc") or "").strip())
    )


def collect_nodes(root, include_all=False):
    nodes = []

    def walk(node, depth):
        if include_all or is_interesting(node):
            rect, center = parse_bounds(node.get("bounds", ""))
            nodes.append({
                "n": len(nodes) + 1,
                "depth": depth,
                "text": node.get("text", ""),
                "desc": node.get("content-desc", ""),
                "id": node.get("resource-id", ""),
                "class": node.get("class", ""),
                "package": node.get("package", ""),
                "clickable": node.get("clickable") == "true",
                "scrollable": node.get("scrollable") == "true",
                "bounds": rect,
                "center": center,
            })
        for child in node:
            walk(child, depth + 1)

    walk(root, 0)
    return nodes


def render_markdown(nodes):
    lines = [
        "| # | text | desc | resource-id | class | click | scroll | center |",
        "|---|------|------|-------------|-------|-------|--------|--------|",
    ]
    for node in nodes:
        text = node["text"].replace("|", "\\|")[:40]
        desc = node["desc"].replace("|", "\\|")[:30]
        res_id = node["id"].replace("|", "\\|")[:48]
        klass = node["class"].rsplit(".", 1)[-1]
        center = "x,y" if node["center"] is None else "{},{}".format(*node["center"])
        lines.append("| N{} | {} | {} | {} | {} | {} | {} | {} |".format(
            node["n"], text or " ", desc or " ", res_id or " ", klass,
            "Y" if node["clickable"] else " ", "Y" if node["scrollable"] else " ",
            center))
    return "\n".join(lines)


def command_dump(args):
    xml_text = dump_hierarchy_xml(args.serial)
    root = ET.fromstring(xml_text)
    nodes = collect_nodes(root, include_all=args.all)
    if not nodes:
        raise RuntimeError("no interactive nodes found (screen locked?)")
    if args.json:
        print(json.dumps(nodes, ensure_ascii=False, indent=2))
    else:
        print(render_markdown(nodes))
        print()
        print("tap:    adb shell input tap <x> <y>")
        print("text:   adb shell input text 'hello%%sworld'   (%s = space)")
        print("key:    adb shell input keyevent 4            (BACK)")


def command_screen(args):
    data = run_adb(["exec-out", "screencap", "-p"], args.serial, binary=True)
    if len(data) < 16 or not data.startswith(b"\x89PNG"):
        raise RuntimeError("screencap returned no PNG frame (secure surface?)")
    with open(args.output, "wb") as handle:
        handle.write(data)
    print("wrote {} ({} bytes)".format(args.output, len(data)))


def main():
    parser = argparse.ArgumentParser(
        description="Android semantic UI bridge over plain adb")
    parser.add_argument("--serial", default=None,
                        help="adb device serial (default: single device)")
    sub = parser.add_subparsers(dest="command", required=True)

    dump = sub.add_parser("dump", help="dump interactive UI nodes")
    dump.add_argument("--json", action="store_true", help="JSON output")
    dump.add_argument("--all", action="store_true",
                      help="include non-interactive nodes")
    dump.set_defaults(handler=command_dump)

    screen = sub.add_parser("screen", help="capture a PNG screenshot")
    screen.add_argument("output", nargs="?", default="screen.png")
    screen.set_defaults(handler=command_screen)

    args = parser.parse_args()
    try:
        args.handler(args)
    except RuntimeError as error:
        print("error: {}".format(error), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
