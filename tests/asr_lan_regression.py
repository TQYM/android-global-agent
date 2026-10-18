#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""F05/F09 Android/F11 动态回归：原样编译 ASR/Llm/LAN 业务类。

依赖 Python 3、JDK 11+；不下载库，不要求 Android SDK、设备或真实凭据。
ASR/Chat 的 HttpURLConnection 由 JVM 内存响应拦截；LAN 运行真实 localhost TCP、
真实 AES-GCM 和临时 JSONL。Android、Whisper JNI、Keystore 与 TLS 平台层使用测试桩。
不把这个测试当真机、TLS/Keystore 或实际语音识别质量验收。
"""
from pathlib import Path
import importlib.util
import os
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "agent-lite/src/com/dsh/agentlite"
FIXTURES = ROOT / "tests/asr_lan_fixtures"

spec = importlib.util.spec_from_file_location("credential_fixture", ROOT / "tests/credential_route_guard.py")
credential_fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(credential_fixture)
STUBS = dict(credential_fixture.STUBS)
STUBS.update({
    "android/content/Context.java": r'''
package android.content;
import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;
public class Context {
    public static final int MODE_PRIVATE = 0;
    private final SharedPreferences prefs = new SharedPreferences();
    public final File filesRoot;
    public final AtomicInteger directoryLookups = new AtomicInteger();
    public volatile boolean failDirectory;
    public Context(File root) { filesRoot = root; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
    public Context getApplicationContext() { return this; }
    public File getExternalFilesDir(String kind) {
        directoryLookups.incrementAndGet();
        if (failDirectory) throw new IllegalStateException("fixture storage failure");
        return filesRoot;
    }
}
''',
    "android/util/Base64.java": r'''
package android.util;
public class Base64 {
    public static final int DEFAULT = 0, NO_WRAP = 2;
    public static String encodeToString(byte[] value, int flags) {
        return java.util.Base64.getEncoder().encodeToString(value);
    }
    public static byte[] decode(String value, int flags) {
        return java.util.Base64.getDecoder().decode(value);
    }
}
''',
    "android/util/Log.java": r'''
package android.util;
public class Log {
    public static final java.util.List<String> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
    public static int i(String tag, String text) { messages.add(text); return 0; }
    public static int w(String tag, String text) { messages.add(text); return 0; }
}
''',
    "com/dsh/agentlite/WhisperAsr.java": r'''
package com.dsh.agentlite;
public class WhisperAsr {
    public static boolean library = true, model = true, loads = true;
    public static int instances, loadCalls, transcribeCalls;
    public static String result = "本地转写 fixture";
    public static Exception failure;
    public WhisperAsr() { instances++; }
    public static boolean available() { return library; }
    public static boolean modelReady(android.content.Context ctx) { return model; }
    public boolean load(android.content.Context ctx) { loadCalls++; return loads; }
    public static float[] wavToPcm(byte[] wav) { return new float[] {0.1f}; }
    public String transcribe(float[] pcm) throws Exception {
        transcribeCalls++;
        if (failure != null) throw failure;
        return result;
    }
    public static void reset() {
        library = model = loads = true;
        instances = loadCalls = transcribeCalls = 0;
        result = "本地转写 fixture";
        failure = null;
    }
}
''',
    "com/dsh/agentlite/AgentEngine.java": r'''
package com.dsh.agentlite;
public class AgentEngine {
    public static void staticLog(String text) { android.util.Log.i("fixture", text); }
}
''',
})
# 使用功能 JSON，不复用凭据测试只支持 {} 的解析桩。
for name in ("JSONObject.java", "JSONArray.java", "JsonCodec.java"):
    STUBS[f"org/json/{name}"] = (FIXTURES / name).read_text(encoding="utf-8")
STUBS["android/content/SharedPreferences.java"] = STUBS["android/content/SharedPreferences.java"].replace(
    "values = new HashMap<>()", "values = new java.util.concurrent.ConcurrentHashMap<>()"
)


def main():
    # 路径由 TemporaryDirectory 创建并校验；只清理本次测试独立临时目录。
    with tempfile.TemporaryDirectory(prefix="asr_lan_") as temp:
        work = Path(temp).resolve()
        if work.parent != Path(tempfile.gettempdir()).resolve() or not work.name.startswith("asr_lan_"):
            raise RuntimeError("test temporary directory mismatch")
        sources = []
        for name, content in STUBS.items():
            target = work / "src" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
            sources.append(str(target))
        sources.extend(str(SRC / name) for name in (
            "Prefs.java", "FeatureFlags.java", "CredentialRoute.java", "LlmClient.java", "AsrRouter.java", "LanSync.java"
        ))
        sources.append(str(ROOT / "tests/TestsAsrLan.java"))
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([
            "javac", "--release", os.getenv("JAVA_RELEASE", "11"), "-encoding", "UTF-8",
            "-d", str(classes), *sources
        ], cwd=ROOT, check=True)
        fixtures = work / "data"
        fixtures.mkdir()
        return subprocess.run([
            "java", "-cp", str(classes), "com.dsh.agentlite.TestsAsrLan", str(fixtures)
        ], cwd=ROOT).returncode


if __name__ == "__main__":
    sys.exit(main())
