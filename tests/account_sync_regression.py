#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""账号/同步/远程任务动态回归：原样编译 Android 生产类，目标字节码 Java 11。

依赖 Python 3、JDK 11+；不下载库，不读取真实凭据，不创建网络 socket。
复用 ASR/LAN 回归的功能 JSON 与少量 Android 桩。HTTP 连接使用 JVM 内存传输，
TaskHistory/MemoryStore 使用独立临时目录中的真实文件。SharedPreferences、
Keystore/TLS、无障碍、LAN 传输和 AgentEngine 执行动作属于可注入测试桩，
不把这些回归当作 APK、真机、TLS 或 Android 磁盘耐久性验收。
"""
from pathlib import Path
import importlib.util
import os
import shutil
import subprocess
import sys
import tempfile

# 导入已有桩时不在共享仓库中产生 __pycache__。
sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "agent-lite/src/com/dsh/agentlite"
spec = importlib.util.spec_from_file_location("account_sync_asr_fixture", ROOT / "tests/asr_lan_regression.py")
asr_fixture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(asr_fixture)
STUBS = {name: asr_fixture.STUBS[name] for name in (
    "android/util/Log.java", "com/dsh/agentlite/DevConfig.java", "com/dsh/agentlite/TokenStore.java",
    "org/json/JSONObject.java", "org/json/JSONArray.java", "org/json/JsonCodec.java",
)}

# 复用同一功能 JSON 编解码，仅补本回归所需的 Android org.json 兼容访问方法。
STUBS["org/json/JSONObject.java"] = STUBS["org/json/JSONObject.java"].replace(
    "    @Override public String toString()", """
    public int length() { return values.size(); }
    public java.util.Iterator<String> keys() { return values.keySet().iterator(); }
    public Object get(String key) {
        if (!values.containsKey(key)) throw new IllegalArgumentException("missing key: " + key);
        return values.get(key);
    }
    public int optInt(String key) { return optInt(key, 0); }
    public long optLong(String key) { return optLong(key, 0L); }
    @Override public String toString()"""
)
STUBS["org/json/JSONArray.java"] = STUBS["org/json/JSONArray.java"].replace(
    "    @Override public String toString()", """
    public String optString(int index, String fallback) {
        Object value = index < 0 || index >= values.size() ? null : values.get(index);
        return value == null ? fallback : String.valueOf(value);
    }
    @Override public String toString()"""
)
STUBS.update({
    "android/content/SharedPreferences.java": r'''
package android.content;
import java.util.*;
/** 可注入两类 commit 失败；memoryFirst 模拟 Android 先更新内存再失败的语义。 */
public final class SharedPreferences {
    private final Map<String,Object> values = new HashMap<>(), disk = new HashMap<>();
    public int commitCalls, applyCalls;
    private int failures;
    private boolean memoryFirst;
    public Set<String> lastCommitKeys = Collections.emptySet();
    public synchronized void failNextCommit(boolean updateMemory) { failures++; memoryFirst = updateMemory; }
    public synchronized void reloadFromDisk() { values.clear(); values.putAll(disk); }
    public synchronized Map<String,?> diskSnapshot() { return new HashMap<>(disk); }
    public synchronized boolean contains(String k) { return values.containsKey(k); }
    public synchronized String getString(String k, String d) { return (String) values.getOrDefault(k,d); }
    public synchronized boolean getBoolean(String k, boolean d) { return (Boolean) values.getOrDefault(k,d); }
    public synchronized int getInt(String k, int d) { return (Integer) values.getOrDefault(k,d); }
    public synchronized long getLong(String k, long d) { return (Long) values.getOrDefault(k,d); }
    public synchronized Map<String,?> getAll() { return new HashMap<>(values); }
    public Editor edit() { return new Editor(); }
    public final class Editor {
        private final Map<String,Object> pending = new LinkedHashMap<>();
        public Editor putString(String k, String v) { pending.put(k,v); return this; }
        public Editor putBoolean(String k, boolean v) { pending.put(k,v); return this; }
        public Editor putInt(String k, int v) { pending.put(k,v); return this; }
        public Editor putLong(String k, long v) { pending.put(k,v); return this; }
        public Editor remove(String k) { pending.put(k,null); return this; }
        private void update() { pending.forEach((k,v) -> { if(v==null) values.remove(k); else values.put(k,v); }); }
        public boolean commit() {
            synchronized (SharedPreferences.this) {
                commitCalls++;
                lastCommitKeys = new HashSet<>(pending.keySet());
                if (failures > 0) { failures--; if (memoryFirst) update(); return false; }
                update(); disk.clear(); disk.putAll(values); return true;
            }
        }
        public void apply() {
            synchronized (SharedPreferences.this) {
                applyCalls++; update(); disk.clear(); disk.putAll(values);
            }
        }
    }
}
''',
    "android/content/Context.java": r'''
package android.content;
import java.io.File;
public final class Context {
    public static final int MODE_PRIVATE = 0;
    private final SharedPreferences prefs = new SharedPreferences();
    private final File root;
    public Context(File root) { this.root = root; }
    public SharedPreferences getSharedPreferences(String name, int mode) { return prefs; }
    public Context getApplicationContext() { return this; }
    public File getExternalFilesDir(String kind) { return root; }
    public File getFilesDir() { return root; }
    public Object getContentResolver() { return this; }
    public Intent registerReceiver(Object ignored, IntentFilter filter) { return new Intent(); }
}
''',
    "android/content/Intent.java": r'''
package android.content;
public class Intent {
    public static final String ACTION_BATTERY_CHANGED = "battery";
    public int getIntExtra(String key, int fallback) {
        if ("level".equals(key)) return 76;
        if ("scale".equals(key)) return 100;
        if ("status".equals(key)) return 2;
        return fallback;
    }
}
''',
    "android/content/IntentFilter.java": "package android.content; public class IntentFilter { public IntentFilter(String name) {} }",
    "android/os/Handler.java": "package android.os; public class Handler { public Handler(Looper l) {} public boolean post(Runnable r) { r.run(); return true; } }",
    "android/os/Looper.java": "package android.os; public class Looper { public static Looper getMainLooper() { return new Looper(); } }",
    "android/os/BatteryManager.java": "package android.os; public class BatteryManager { public static final String EXTRA_LEVEL=\"level\", EXTRA_SCALE=\"scale\", EXTRA_STATUS=\"status\"; public static final int BATTERY_STATUS_CHARGING=2, BATTERY_STATUS_FULL=5; }",
    "android/os/Build.java": "package android.os; public class Build { public static final String MANUFACTURER=\"fixture\", MODEL=\"JVM\", DEVICE=\"memory\"; }",
    "android/provider/Settings.java": "package android.provider; public class Settings { public static class Secure { public static final String ANDROID_ID=\"android_id\"; public static String getString(Object resolver, String key) { return \"fixture-id\"; } } }",
    "com/dsh/agentlite/TlsPin.java": r'''
package com.dsh.agentlite;
public final class TlsPin {
    public static boolean fail;
    public static int calls;
    public static javax.net.ssl.SSLSocketFactory factory() {
        calls++;
        if (fail) throw new IllegalStateException("fixture TLS factory failure");
        return (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
    }
}
''',
    "com/dsh/agentlite/AccountClient.java": r'''
package com.dsh.agentlite;
public final class AccountClient {
    public static void bindDeviceSync(android.content.Context ctx, Prefs p) throws Exception {
        throw new AssertionError("fixture expects an already bound device");
    }
}
''',
    "com/dsh/agentlite/A11yGuard.java": "package com.dsh.agentlite; public final class A11yGuard { public static boolean isEnabledFlag(android.content.Context c) { return AgentA11yService.get() != null; } }",
    "com/dsh/agentlite/AgentA11yService.java": "package com.dsh.agentlite; public final class AgentA11yService { public static boolean available=true; public static AgentA11yService get() { return available ? new AgentA11yService() : null; } }",
    "com/dsh/agentlite/LanSync.java": r'''
package com.dsh.agentlite;
import java.util.*;
public final class LanSync {
    public static final int DEFAULT_PORT=18444;
    public static boolean active;
    public static int calls, merged=2;
    public static org.json.JSONArray sent;
    public static boolean enabled(android.content.Context ctx) { return active; }
    public static List<String> lanIPs() { return Collections.singletonList("192.168.8.10"); }
    public static int port(android.content.Context ctx) { return DEFAULT_PORT; }
    public static int syncWithPeer(android.content.Context ctx, String ip, int port, String id,
            org.json.JSONArray tasks, long cursor) { calls++; sent=tasks; return merged; }
    /** 桩仅提供未解析的输入文件；peer 的校验、增量组装、游标更新全部由生产 SyncClient 执行。 */
    public static List<String> pendingPeerRecords(android.content.Context ctx) {
        java.nio.file.Path file = new java.io.File(ctx.getFilesDir(), "fixture_peer_records.jsonl").toPath();
        try { return java.nio.file.Files.exists(file) ? java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8) : new ArrayList<>(); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    public static void reset() { active=false; calls=0; merged=2; sent=null; }
}
''',
    "com/dsh/agentlite/AgentEngine.java": r'''
package com.dsh.agentlite;
/** 仅实现生产 ResultListener 的执行边界，不复制 RemoteCommand 的状态机。 */
public final class AgentEngine {
    public interface ResultListener { void onResult(long runId, String commandId, String result, String detail); }
    public static final class Execution {
        public final long seq;
        public final String task, command;
        public final ResultListener listener;
        Execution(long seq, String task, String command, ResultListener listener) {
            this.seq=seq; this.task=task; this.command=command; this.listener=listener;
        }
        public void deliver(String id, String result, String detail) { listener.onResult(seq, id, result, detail); }
    }
    private static final AgentEngine INSTANCE=new AgentEngine();
    public boolean running, accept=true;
    public int attempts, executions;
    public long nextSeq=1001;
    public Execution current;
    public Runnable beforeStart;
    public static AgentEngine get(android.content.Context ctx) { return INSTANCE; }
    public boolean isRunning() { return running; }
    public boolean start(String task, String commandId, ResultListener completion) {
        attempts++;
        if (beforeStart != null) beforeStart.run();
        if (!accept || running) return false;
        executions++; running=true;
        current=new Execution(nextSeq++, task, commandId, completion);
        return true;
    }
    public Execution finish(String result, String detail) {
        Execution e=current; running=false; e.deliver(e.command, result, detail); return e;
    }
    public void reset() { running=false; accept=true; attempts=executions=0; nextSeq=1001; current=null; beforeStart=null; }
    public static void staticLog(String text) { android.util.Log.i("fixture", text); }
}
''',
})


def executable(name):
    home = os.getenv("JAVA_HOME")
    path = str(Path(home) / "bin" / name) if home else shutil.which(name)
    if not path or not Path(path).is_file():
        raise RuntimeError(f"missing Java executable: {name}")
    return path


def main():
    javac, java = executable("javac"), executable("java")
    compiler = subprocess.run([javac, "-version"], text=True, capture_output=True, check=True)
    runtime = subprocess.run([java, "-version"], text=True, capture_output=True, check=True)
    print("Compiler: " + (compiler.stdout + compiler.stderr).strip(), flush=True)
    print("Runtime: " + (runtime.stdout + runtime.stderr).splitlines()[0], flush=True)
    print("Production bytecode target: Java 11 (--release 11); HTTP sockets: 0", flush=True)
    with tempfile.TemporaryDirectory(prefix="account_sync_") as temp:
        work = Path(temp).resolve()
        if work.parent != Path(tempfile.gettempdir()).resolve() or not work.name.startswith("account_sync_"):
            raise RuntimeError("test temporary directory mismatch")
        sources = []
        for name, content in STUBS.items():
            target = work / "src" / name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")
            sources.append(str(target))
        production = (
            "Prefs.java", "FeatureFlags.java", "CredentialRoute.java", "ApiException.java",
            "AccountHttp.java", "SyncClient.java", "RemoteCommand.java", "TaskHistory.java", "MemoryStore.java",
        )
        sources.extend(str(SRC / name) for name in production)
        sources.append(str(ROOT / "tests/TestsAccountSync.java"))
        classes = work / "classes"
        classes.mkdir()
        subprocess.run([
            javac, "--release", "11", "-encoding", "UTF-8", "-d", str(classes), *sources
        ], cwd=ROOT, check=True, timeout=90)
        for name in production:
            bytecode = classes / "com/dsh/agentlite" / (name[:-5] + ".class")
            if int.from_bytes(bytecode.read_bytes()[6:8], "big") != 55:
                raise RuntimeError(f"incorrect Java 11 class version: {name}")
        data = work / "data"
        data.mkdir()
        return subprocess.run([
            java, "-cp", str(classes), "com.dsh.agentlite.TestsAccountSync", str(data)
        ], cwd=ROOT, timeout=90).returncode


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.CalledProcessError, subprocess.TimeoutExpired) as exc:
        print(f"账号/同步回归执行失败: {exc}", file=sys.stderr)
        sys.exit(2)
