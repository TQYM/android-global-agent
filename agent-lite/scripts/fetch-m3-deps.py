#!/usr/bin/env python3
"""递归下载 Material 3 依赖（AAR/JAR）到 /tmp/m3lib/deps/。

手工构建（无 Gradle）需要自行解析 POM 依赖树并下载产物。
用法: python3 fetch_m3_deps.py [groupId:artifactId:version] ...
"""
import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

BASE = "https://maven.aliyun.com/repository/google"
MAVEN_CENTRAL = "https://maven.aliyun.com/repository/public"
OUT = os.environ.get("M3_DEPS_OUT", os.path.expanduser("~/.agent-lite-m3-deps"))
NS = "{http://maven.apache.org/POM/4.0.0}"

seen = set()
queue = []


def path_of(g, a, v, ext):
    return f"{g.replace('.', '/')}/{a}/{v}/{a}-{v}.{ext}"


def fetch(url, dest, timeout=180):
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        return True
    r = subprocess.run(["curl", "-sS", "-m", str(timeout), "-o", dest, "-w", "%{http_code}", url],
                       capture_output=True, text=True)
    code = r.stdout.strip()
    if code == "200" and os.path.exists(dest) and os.path.getsize(dest) > 0:
        return True
    if os.path.exists(dest):
        os.remove(dest)
    return False


def parse_pom(g, a, v):
    """下载并解析 POM，返回依赖列表 [(g,a,v,scope)]。"""
    pom = os.path.join(OUT, path_of(g, a, v, "pom").replace("/", "_"))
    urls = [f"{BASE}/{path_of(g, a, v, 'pom')}", f"{MAVEN_CENTRAL}/{path_of(g, a, v, 'pom')}"]
    ok = False
    for u in urls:
        if fetch(u, pom, timeout=90):
            ok = True
            break
    if not ok:
        print(f"  ! POM 取不到: {g}:{a}:{v}")
        return []
    try:
        root = ET.parse(pom).getroot()
    except Exception as e:
        print(f"  ! POM 解析失败 {a}: {e}")
        return []
    deps = []
    parent_v = None
    parent = root.find(NS + "parent")
    # 依赖版本可能省略（继承自 dependencyManagement）——此处只处理显式版本
    for d in root.findall(f"{NS}dependencies/{NS}dependency"):
        dg = d.findtext(NS + "groupId", "")
        da = d.findtext(NS + "artifactId", "")
        dv = d.findtext(NS + "version", "")
        sc = d.findtext(NS + "scope", "compile")
        if not dg or not da:
            continue
        if sc in ("test", "provided", "import", "system"):
            continue
        if not dv or "${" in dv:
            continue          # 版本由 BOM/父 POM 管理，跳过（Material 的直接依赖已显式给出）
        deps.append((dg, da, dv, sc))
    return deps


def download_artifact(g, a, v):
    """下载产物（优先 AAR，其次 JAR）。"""
    key = f"{g}:{a}:{v}"
    if key in seen:
        return None
    seen.add(key)
    for ext in ("aar", "jar"):
        name = path_of(g, a, v, ext).replace("/", "_")
        dest = os.path.join(OUT, name)
        for base in (BASE, MAVEN_CENTRAL):
            if fetch(f"{base}/{path_of(g, a, v, ext)}", dest, timeout=240):
                size = os.path.getsize(dest)
                print(f"  ✓ {key} [{ext}] {size//1024}KB")
                return dest
    print(f"  ✗ {key} 下载失败")
    return None


def main():
    os.makedirs(OUT, exist_ok=True)
    roots = sys.argv[1:] or ["com.google.android.material:material:1.12.0",
                             "androidx.appcompat:appcompat:1.6.1",
                             "androidx.core:core:1.12.0"]
    for r in roots:
        g, a, v = r.split(":")
        queue.append((g, a, v))
    while queue:
        g, a, v = queue.pop(0)
        key = f"{g}:{a}:{v}"
        if key in seen:
            continue
        art = download_artifact(g, a, v)
        if art:
            for dg, da, dv, sc in parse_pom(g, a, v):
                if f"{dg}:{da}:{dv}" not in seen:
                    queue.append((dg, da, dv))
    files = [f for f in os.listdir(OUT) if f.endswith((".aar", ".jar"))]
    total = sum(os.path.getsize(os.path.join(OUT, f)) for f in files)
    print(f"\n=== 完成：{len(files)} 个产物，共 {total//1024//1024}MB ===")


if __name__ == "__main__":
    main()
