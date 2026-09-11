#!/usr/bin/env python3
"""probe.py — NEXT 真机首日命令可用性探测（0.5d 探针，规划 R2 止损机制）。

用法: python3 tools/harmony-agent/probe.py [--serial SN]
输出: 命令可用性表（stdout + probe-report.json），真机到达后第一时间执行，
      dumpLayout/uiInput/截图任一失败即按规划止损评估。
"""
import argparse
import json
import sys
import time
import os
import traceback

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import hdc

PROBES = [
    # (名称, 函数, 描述)
    ("list_targets", lambda s: hdc.list_targets(), "设备枚举"),
    ("screen_size", lambda s: hdc.screen_size(s), "屏幕尺寸获取"),
    ("dumpLayout", lambda s: len(hdc.dump_layout(s)) > 0, "控件树导出（感知核心）"),
    ("collect_nodes", lambda s: hdc.collect_nodes(s), "节点表展平（可点排序）"),
    ("screenshot", lambda s: hdc.screenshot(s, "/tmp/agent_probe.jpeg"), "截图（snapshot_display，.jpeg 后缀）"),
]


def exec_probe(serial, name, fn):
    try:
        t0 = time.time()
        result = fn(serial)
        return {"name": name, "ok": True, "secs": round(time.time() - t0, 1),
                "detail": str(result)[:200]}
    except Exception as e:
        return {"name": name, "ok": False, "secs": -1,
                "detail": f"{type(e).__name__}: {str(e)[:300]}"}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--serial", default=None)
    ap.add_argument("--inject", action="store_true",
                    help="注入测试：click(10,10) + 截图后回读验证（慎用，会真的点一下）")
    args = ap.parse_args()

    serial = args.serial
    if not serial:
        try:
            targets = hdc.list_targets()
        except hdc.HdcError as e:
            print(str(e), file=sys.stderr)
            sys.exit(2)
        if not targets:
            print("无 hdc 目标设备：请连接 NEXT 真机并开启开发者模式（USB 调试）", file=sys.stderr)
            sys.exit(2)
        serial = targets[0]
    print(f"=== HDC 命令可用性探测 @ {serial} ===")
    report = {"serial": serial, "time": time.strftime("%Y-%m-%d %H:%M:%S"), "probes": []}
    for name, fn, desc in PROBES:
        r = exec_probe(serial, name, fn)
        r["desc"] = desc
        report["probes"].append(r)
        mark = "✅" if r["ok"] else "❌"
        print(f"{mark} {name:<14} {desc:<20} {r['secs']:>6}s  {r['detail']}")

    if args.inject:
        print("--- 注入测试（会真实点击屏幕 10,10）---")
        for name, fn, desc in [
            ("click", lambda s: hdc.click(s, 10, 10), "uiInput click"),
            ("back", lambda s: hdc.back(s), "keyEvent 返回键码核实"),
        ]:
            r = exec_probe(serial, name, fn)
            r["desc"] = desc
            report["probes"].append(r)
            print(("✅" if r["ok"] else "❌") + f" {name:<14} {desc:<20} {r['detail']}")

    with open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "probe-report.json"), "w") as f:
        json.dump(report, f, ensure_ascii=False, indent=2)
    ok = sum(1 for p in report["probes"] if p["ok"])
    print(f"=== {ok}/{len(report['probes'])} 通过，报告: probe-report.json ===")
    if ok < len(report["probes"]):
        print("有失败项 → 对照 hdc.py 修正命令名/参数（规划 R2：dumpLayout/uiInput/截图任一失败即止损评估）")
        sys.exit(1)


if __name__ == "__main__":
    main()
