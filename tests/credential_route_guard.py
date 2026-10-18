#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""T01/T02 凭据行为回归：原样编译 Prefs/LlmClient，抽取真实主页守卫。

所有 URL 由 JVM 内存连接拦截，绝不创建 socket；凭据全是固定假值。
Android/Keystore/JSON 仅提供编译桩，不据此声称 UI、TLS 或存储安全已验收。
依赖 Python 3 + JDK 11+，无需 SDK、设备、下载或真实账号。
"""
from pathlib import Path
import os
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "agent-lite/src/com/dsh/agentlite"
OUT = ROOT / ".check-tmp/credentials"

STUBS = {
    "android/content/Context.java": r'''
package android.content;
public class Context {
    public static final int MODE_PRIVATE = 0;
    private final SharedPreferences prefs = new SharedPreferences();
    public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
}
''',
    "android/content/SharedPreferences.java": r'''
package android.content;
import java.util.*;
public class SharedPreferences {
    private final Map<String,Object> values = new HashMap<>();
    public boolean contains(String k) { return values.containsKey(k); }
    public String getString(String k, String d) { return (String) values.getOrDefault(k,d); }
    public boolean getBoolean(String k, boolean d) { return (Boolean) values.getOrDefault(k,d); }
    public int getInt(String k, int d) { return (Integer) values.getOrDefault(k,d); }
    public long getLong(String k, long d) { return (Long) values.getOrDefault(k,d); }
    public Map<String,?> getAll() { return new HashMap<>(values); }
    public Editor edit() { return new Editor(); }
    public final class Editor {
        private final Map<String,Object> pending = new HashMap<>();
        public Editor putString(String k, String v) { pending.put(k,v); return this; }
        public Editor putBoolean(String k, boolean v) { pending.put(k,v); return this; }
        public Editor putInt(String k, int v) { pending.put(k,v); return this; }
        public Editor putLong(String k, long v) { pending.put(k,v); return this; }
        public Editor remove(String k) { pending.put(k,null); return this; }
        public boolean commit() { apply(); return true; }
        public void apply() { pending.forEach((k,v) -> { if(v==null) values.remove(k); else values.put(k,v); }); }
    }
}
''',
    "android/util/Log.java": r'''
package android.util;
public class Log {
    public static final java.util.List<String> messages = new java.util.ArrayList<>();
    public static int i(String tag, String msg) { messages.add(msg); return 0; }
    public static int w(String tag, String msg) { messages.add(msg); return 0; }
}
''',
    "android/util/Base64.java": r'''
package android.util;
public class Base64 {
    public static final int NO_WRAP = 2;
    public static String encodeToString(byte[] value, int flags) {
        return java.util.Base64.getEncoder().encodeToString(value);
    }
}
''',
    "com/dsh/agentlite/DevConfig.java": r'''
package com.dsh.agentlite;
public class DevConfig {
    public static final String BASE_URL = "https://own.example.invalid/v1";
    public static final String API_KEY = "";
    public static final String MODEL = "deepseek-flash";
    public static final String SYNC_URL = "https://gateway.example.invalid";
}
''',
    "com/dsh/agentlite/TokenStore.java": r'''
package com.dsh.agentlite;
public class TokenStore {
    private final android.content.SharedPreferences store;
    public TokenStore(android.content.Context ctx) { store=ctx.getSharedPreferences("fixture",0); }
    public String load() { return store.getString("__fixture_token",""); }
    public boolean save(String s) { store.edit().putString("__fixture_token",s).apply(); return true; }
    public void clear() { store.edit().remove("__fixture_token").apply(); }
}
''',
    "com/dsh/agentlite/SyncClient.java": r'''
package com.dsh.agentlite;
public class SyncClient {
    public static String serverBase(android.content.Context ctx) {
        String s = new Prefs(ctx).syncServer();
        return s == null ? "" : s.trim().replaceAll("/+$", "");
    }
}
''',
    "com/dsh/agentlite/TlsPin.java": r'''
package com.dsh.agentlite;
public class TlsPin { public static void apply(java.net.HttpURLConnection c) {} }
''',
    "org/json/JSONObject.java": r'''
package org.json;
public class JSONObject {
    public JSONObject() {}
    public JSONObject(String s) {
        if (!"{}".equals(s.trim())) throw new IllegalArgumentException("JSON 解析不在本测试范围");
    }
    public JSONObject put(String k,Object v) { throw new UnsupportedOperationException(); }
    public boolean has(String k) { return false; }
    public String optString(String k) { return ""; }
    public String optString(String k,String d) { return d; }
    public long optLong(String k,long d) { return d; }
    public JSONObject getJSONObject(String k) { throw new UnsupportedOperationException(); }
    public JSONArray getJSONArray(String k) { throw new UnsupportedOperationException(); }
    public JSONArray optJSONArray(String k) { return null; }
    public JSONObject optJSONObject(String k) { return null; }
    public String getString(String k) { throw new UnsupportedOperationException(); }
}
''',
    "org/json/JSONArray.java": r'''
package org.json;
public class JSONArray {
    public JSONArray put(Object v) { throw new UnsupportedOperationException(); }
    public int length() { return 0; }
    public JSONObject getJSONObject(int n) { throw new UnsupportedOperationException(); }
    public JSONObject optJSONObject(int n) { return null; }
}
''',
}


def main():
    body = (SRC / "MainActivity.java").read_text(encoding="utf-8")
    match = re.search(r"    private boolean hasUsableCredential\(\) \{.*?\n    \}", body, re.S)
    if not match:
        raise RuntimeError("找不到实际主页凭据守卫；禁止用手写替身代替行为测试")
    harness = '''package com.dsh.agentlite;
public final class CredentialEntryHarness {
    private final Prefs prefs;
    public CredentialEntryHarness(Prefs prefs) { this.prefs = prefs; }
''' + match.group(0).replace("private boolean", "public boolean", 1) + "\n}\n"
    sources = []
    for name, content in {**STUBS, "com/dsh/agentlite/CredentialEntryHarness.java": harness}.items():
        target = OUT / "src" / name
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        sources.append(str(target))
    for name in ("Prefs.java", "FeatureFlags.java", "LlmClient.java", "CredentialRoute.java"):
        path = SRC / name
        if path.exists():
            sources.append(str(path))
    sources.append(str(ROOT / "tests/CredentialRouteTest.java"))
    classes = OUT / "classes"
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run(["javac", "--release", os.getenv("JAVA_RELEASE", "11"), "-encoding", "UTF-8",
                    "-d", str(classes), *sources], check=True, cwd=ROOT)
    return subprocess.run(["java", "-cp", str(classes), "com.dsh.agentlite.CredentialRouteTest"],
                          cwd=ROOT).returncode


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError) as exc:
        print(f"凭据行为测试无法执行：{exc}", file=sys.stderr)
        sys.exit(2)
