#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""ui_widget_guard.py — 布局控件类 ↔ Java 转型一致性门禁（守则 §12.1「产物可安装启动」）。

    python3 tests/ui_widget_guard.py

为什么需要：M3 迁移把布局里的 `<Switch>` 换成 `com.google.android.material.materialswitch.MaterialSwitch`，
但 Java 侧的 `private Switch swVision;` / `(Switch) findViewById(...)` 没跟着改。两者**看起来**是
同一个控件，实际 `MaterialSwitch extends SwitchCompat extends CompoundButton`——**不是**
`android.widget.Switch`。于是打开设置页必 `ClassCastException`，而这只有真机点进去才会暴露。

判定不靠猜、也不靠名字后缀（`MaterialSwitch` 恰好以 `Switch` 结尾，名字法会漏）：
对每个「布局里写全限定类」的 id，取出 Java 侧转型用的类型，再用 **javap 从真实 classpath
走继承链**，确认该类型确实是祖先（或同类）。classpath = android.jar + 构建出的依赖 jar
（`agent-lite/build/libs/*/classes.jar`，由 build-mac.sh 展开 AAR 得到）。

用法与退出码：全绿 0；发现不一致 1；缺少 classpath（未构建过）2 —— 此时跳过并提示。
"""
import glob
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "agent-lite", "src", "com", "dsh", "agentlite")
LAYOUTS = os.path.join(ROOT, "agent-lite", "res", "layout")
BUILD_LIBS = os.path.join(ROOT, "agent-lite", "build", "libs")

SDK = os.environ.get("ANDROID_HOME", os.path.expanduser("~/Library/Android/sdk"))
PLATFORMS = sorted(glob.glob(os.path.join(SDK, "platforms", "android-*")))
ANDROID_JAR = os.path.join(PLATFORMS[-1], "android.jar") if PLATFORMS else ""


def classpath():
    """android.jar + 依赖类。

    优先用构建展开出来的 `agent-lite/build/libs/*/classes.jar`；若尚未构建过（干净检出），
    则现场从 M3 依赖 AAR 里取出 classes.jar——这样门禁在首次构建前也能跑，不必先构建再校验。
    """
    if not os.path.exists(ANDROID_JAR):
        return None
    jars = sorted(glob.glob(os.path.join(BUILD_LIBS, "*", "classes.jar")))
    if jars:
        return ":".join([ANDROID_JAR] + jars)

    deps = os.environ.get("M3_DEPS", os.path.expanduser("~/.agent-lite-m3-deps"))
    aars = [a for a in sorted(glob.glob(os.path.join(deps, "*.aar")))
            if any(k in os.path.basename(a) for k in ("material", "appcompat", "core"))]
    if not aars:
        return None
    import tempfile
    import zipfile
    tmp = tempfile.mkdtemp(prefix="ui-widget-guard-")
    for a in aars:
        try:
            with zipfile.ZipFile(a) as z:
                if "classes.jar" in z.namelist():
                    z.extract("classes.jar", tmp)
                    dst = os.path.join(tmp, os.path.basename(a) + ".jar")
                    os.replace(os.path.join(tmp, "classes.jar"), dst)
                    jars.append(dst)
        except Exception:
            continue
    return ":".join([ANDROID_JAR] + jars) if jars else None


def simple_name(fq):
    return fq.rsplit(".", 1)[-1]


def import_map():
    """Java 侧简单名 → 全限定名（含同包类）。"""
    m = {}
    for f in glob.glob(os.path.join(SRC, "*.java")):
        for line in open(f, encoding="utf-8", errors="replace"):
            mm = re.match(r"\s*import\s+(?:static\s+)?([\w.]+);", line)
            if mm:
                m[simple_name(mm.group(1))] = mm.group(1)
    return m


def layout_fq_ids():
    """{id: 全限定控件类}——只收「布局里写了全限定类名」的控件。"""
    out = {}
    for f in glob.glob(os.path.join(LAYOUTS, "*.xml")):
        txt = open(f, encoding="utf-8").read()
        for m in re.finditer(r"<([a-zA-Z][\w.]*\.[A-Z][\w.]*)\b([^>]*?)>", txt, re.S):
            tag, attrs = m.group(1), m.group(2)
            i = re.search(r'android:id="@\+id/(\w+)"', attrs)
            if i:
                out.setdefault(i.group(1), tag)
    return out


def java_id_types():
    """Java 侧 {id: {(类型简单名, 文件:行)}}——覆盖 `T v = findViewById(R.id.x)`、
    `(T) findViewById(R.id.x)`、以及「字段声明 + 单独赋值」三种写法。"""
    found = {}
    for f in sorted(glob.glob(os.path.join(SRC, "*.java"))):
        code = open(f, encoding="utf-8", errors="replace").read()
        rel = os.path.relpath(f, ROOT)
        field_type = dict(re.findall(r"\b(?:private|protected|public)\s+([A-Z]\w+)\s+(\w+)\s*;", code))
        for i, line in enumerate(code.splitlines(), 1):
            # 类型允许全限定写法（android.widget.Switch / androidx.x.Y）——早期版本只认简单名，
            # 于是 `(android.widget.Switch) findViewById(...)` 被静默跳过，门禁形同虚设。
            TYPE = r"((?:[a-z][\w]*\.)*[A-Z]\w*)"
            for m in re.finditer(r"\b" + TYPE + r"\s+(\w+)\s*=\s*findViewById\(\s*R\.id\.(\w+)\s*\)", line):
                found.setdefault(m.group(3), set()).add((m.group(1), "%s:%d" % (rel, i)))
            for m in re.finditer(r"\(\s*" + TYPE + r"\s*\)\s*findViewById\(\s*R\.id\.(\w+)\s*\)", line):
                found.setdefault(m.group(2), set()).add((m.group(1), "%s:%d" % (rel, i)))
            for m in re.finditer(r"\b(\w+)\s*=\s*findViewById\(\s*R\.id\.(\w+)\s*\)", line):
                t = field_type.get(m.group(1))
                if t:
                    found.setdefault(m.group(2), set()).add((t, "%s:%d" % (rel, i)))
    return found


_ANC_CACHE = {}


def ancestors(fq, cp):
    """沿真实继承链向上走，返回祖先集合（用 javap 读 classpath 里的字节码，结果缓存）。"""
    if fq in _ANC_CACHE:
        return _ANC_CACHE[fq]
    check = _ancestors_uncached(fq, cp)
    _ANC_CACHE[fq] = check[0]
    _ANC_CACHE[fq + "|chain"] = check[1]
    return check[0]


def _ancestors_uncached(fq, cp):
    chain, order, cur, guard = set(), [fq], fq, 0
    while cur and cur != "java.lang.Object" and guard < 12:
        guard += 1
        r = subprocess.run(["javap", "-cp", cp, cur], capture_output=True, text=True)
        m = re.search(r"\bextends\s+([\w.$]+)", r.stdout)
        if not m:
            break
        cur = m.group(1)
        chain.add(cur)
        order.append(cur)
    return chain, order


def main():
    cp = classpath()
    if cp is None:
        print("!! 缺少 classpath（android.jar 或 agent-lite/build/libs/*/classes.jar）——先构建一次：")
        print("     cd agent-lite && sh build-mac.sh")
        return 2

    fq_ids = layout_fq_ids()
    java_types = java_id_types()
    imports = import_map()

    print("=" * 78)
    print("布局控件类 ↔ Java 转型一致性门禁")
    print("=" * 78)
    print("布局中全限定控件的 id：%d 个，其中被 Java 转型的：%d 个"
          % (len(fq_ids), len([k for k in fq_ids if k in java_types])))
    print()

    checked, bad = 0, []
    for iid, tag in sorted(fq_ids.items()):
        for tsimple, where in sorted(java_types.get(iid, ())):
            # 全限定写法直接用；简单名先查 import，查不到按同包类处理
            tfq = tsimple if "." in tsimple else imports.get(tsimple, "com.dsh.agentlite." + tsimple)
            if tfq == tag or tfq in ancestors(tag, cp) or tsimple == simple_name(tag):
                checked += 1
            else:
                bad.append((iid, tag, tfq, where))

    if bad:
        print("发现 %d 处不一致（布局控件与 Java 转型不是同一类型，也不是其祖先——运行时会 ClassCastException）：" % len(bad))
        for iid, tag, tfq, where in bad:
            print("  FAIL  id=%-16s 布局=%-56s Java=%s  (%s)" % (iid, tag, tfq, where))
            print("        实际继承链：%s" % " → ".join(_ANC_CACHE.get(tag + "|chain", [tag])[:5]))
        print()
        print("通过 %d 项，失败 %d 项" % (checked, len(bad)))
        return 1

    print("  PASS  %d 处转型全部与布局控件类型一致（或为其祖先）" % checked)
    print()
    print("通过 %d 项，失败 0 项" % checked)
    print("控件类型一致 ✅")
    return 0


if __name__ == "__main__":
    sys.exit(main())
