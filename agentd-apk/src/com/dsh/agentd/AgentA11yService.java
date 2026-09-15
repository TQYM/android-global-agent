// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentd;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.SecureRandom;

/**
 * Resident perception service: holds a first-class accessibility connection
 * (unlike the flaky uiautomator instrumentation bridge) and serves the
 * interactive-node tree as JSON on 127.0.0.1:8081 for agentd to consume.
 * Localhost only — nothing leaves the device.
 */
public class AgentA11yService extends AccessibilityService {

    private static final String TAG = "AgentA11y";
    private static final int PORT = 8081;
    /** 桥接令牌文件名（落在本应用私有目录，同机其他 App 读不到）。 */
    private static final String TOKEN_FILE = "bridge.token";
    /** 令牌最小长度（hex(32 字节) = 64 字符），低于此值视为损坏并重新生成。 */
    private static final int TOKEN_MIN_LEN = 32;
    /** 网关客户端要带的头名，与 agentd-go/internal/device 约定一致。 */
    private static final String TOKEN_HEADER = "X-Agentd-Bridge";

    private Thread serverThread;
    /**
     * 桥接令牌；null = 生成/落盘失败，此时退化为「仅回环 + 方法白名单」模式。
     *
     * <p>为什么要令牌（手册 §15.1 P0-1）：Android 的 loopback 不是应用级安全边界，
     * 同机任意 App 都能连 127.0.0.1:8081 读屏（/nodes）或注入文本（/settext）。
     * 令牌存在本应用私有目录，只有本应用与 root（agentd）读得到。
     */
    private volatile String bridgeToken;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        android.util.Log.i(TAG, "onServiceConnected: service bound");
        ensureToken();
        if (serverThread == null || !serverThread.isAlive()) {
            serverThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    serve();
                }
            });
            serverThread.setDaemon(true);
            serverThread.start();
        }
    }

    /**
     * 读取（或首次生成）桥接令牌，写入 {@code files/bridge.token}。
     *
     * <p>失败时不抛出：安全加固不该把感知通道整个打死——降级为仅回环监听，
     * 并留下一条只含异常类型的日志。
     */
    private void ensureToken() {
        try {
            File f = new File(getFilesDir(), TOKEN_FILE);
            if (f.isFile()) {
                String t = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
                if (t.length() >= TOKEN_MIN_LEN) {
                    bridgeToken = t;
                    return;
                }
            }
            byte[] raw = new byte[32];
            new SecureRandom().nextBytes(raw);
            StringBuilder sb = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                sb.append(String.format("%02x", b));
            }
            String token = sb.toString();
            Files.write(f.toPath(), (token + "\n").getBytes(StandardCharsets.UTF_8));
            bridgeToken = token;
            android.util.Log.i(TAG, "bridge token 已生成（应用私有目录，仅本应用与 root 可读）");
        } catch (Exception e) {
            bridgeToken = null;
            android.util.Log.w(TAG, "bridge token 不可用，回退为仅回环监听："
                    + e.getClass().getSimpleName());
        }
    }

    private void serve() {
        // Retry with backoff: a transient bind failure (or a race with the
        // system during reconnect) must not strand the service until the
        // next a11y rebind.
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                // 显式绑定回环：无参构造器等价于 0.0.0.0，局域网内任何设备
                // 都能直连 8081 读屏/注入文本（手册 §15.1 P0-1）。
                ServerSocket ss = new ServerSocket(PORT, 50, InetAddress.getLoopbackAddress());
                android.util.Log.i(TAG, "listening on 127.0.0.1:" + PORT
                        + (bridgeToken != null ? " (token required)" : " (token unavailable)"));
                while (true) {
                    try {
                        Socket s = ss.accept();
                        handle(s);
                    } catch (IOException ignored) {
                    }
                }
            } catch (IOException e) {
                android.util.Log.e("AgentA11y", "server socket failed (attempt "
                        + (attempt + 1) + ")", e);
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    private void handle(Socket s) {
        try {
            // Read headers byte-wise up to CRLFCRLF (cap 8 KB), then read
            // exactly Content-Length BYTES of body from the same stream —
            // Content-Length counts bytes, so a char Reader would starve
            // on multi-byte UTF-8 bodies (CJK text).
            java.io.InputStream raw = s.getInputStream();
            java.io.ByteArrayOutputStream head = new java.io.ByteArrayOutputStream();
            int b;
            int matched = 0; // progress through \r\n\r\n
            while (head.size() < 8192 && (b = raw.read()) != -1) {
                head.write(b);
                if ((matched == 0 || matched == 2) && b == '\r') {
                    matched++;
                } else if ((matched == 1 || matched == 3) && b == '\n') {
                    matched++;
                } else {
                    matched = (b == '\r') ? 1 : 0;
                }
                if (matched == 4) {
                    break;
                }
            }
            String headStr = head.toString("UTF-8");
            String[] lines = headStr.split("\r\n");
            if (lines.length == 0 || lines[0].isEmpty()) {
                s.close();
                return;
            }
            String requestLine = lines[0];
            int contentLength = 0;
            for (String h : lines) {
                int colon = h.indexOf(':');
                if (colon > 0 && h.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    try {
                        contentLength = Integer.parseInt(h.substring(colon + 1).trim());
                    } catch (NumberFormatException ignored) {
                    }
                }
            }

            // 鉴权：令牌在不匹配时拒绝。先把 body 读掉再回应——直接 close 会
            // 发 RST，客户端可能收不到 401 而是看到连接被重置。
            String expected = bridgeToken;
            if (expected != null && !expected.equals(headerValue(lines, TOKEN_HEADER))) {
                drain(raw, Math.min(contentLength, 64 * 1024));
                respond(s, "401 Unauthorized", "application/json",
                        "{\"ok\":false,\"error\":\"bridge token 缺失或不匹配\"}");
                s.close();
                return;
            }
            if (requestLine.startsWith("GET /nodes")) {
                respond(s, "200 OK", "application/json", nodesJson());
            } else if (requestLine.startsWith("POST /settext")) {
                byte[] buf = new byte[Math.max(0, Math.min(contentLength, 64 * 1024))];
                int off = 0;
                while (off < buf.length) {
                    int n = raw.read(buf, off, buf.length - off);
                    if (n < 0) {
                        break;
                    }
                    off += n;
                }
                respond(s, "200 OK", "application/json",
                        setTextJson(new String(buf, 0, off, "UTF-8")));
            } else {
                respond(s, "404 Not Found", "text/plain", "not found");
            }
            s.close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Types text into the focused editable node via ACTION_SET_TEXT — the
     * only channel that handles CJK/Unicode (shell `input text` is ASCII
     * only). Body: {"text":"...","append":false}. The text replaces the
     * field content unless append is true.
     */
    private String setTextJson(String body) {
        JSONObject out = new JSONObject();
        try {
            JSONObject req = new JSONObject(body);
            String text = req.optString("text");
            boolean append = req.optBoolean("append", false);
            if (text.isEmpty()) {
                out.put("ok", false).put("error", "text is empty");
                return out.toString();
            }
            final AccessibilityNodeInfo target = findEditableTarget();
            if (target == null) {
                out.put("ok", false)
                        .put("error", "no editable node focused; tap an input field first");
                return out.toString();
            }
            String current = cs(target.getText());
            String next = append ? current + text : text;
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, next);
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            boolean done = target.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT, args);
            out.put("ok", done).put("replaced", !append).put("length", next.length());
            if (!done) {
                out.put("error", "ACTION_SET_TEXT rejected by node");
            }
        } catch (JSONException e) {
            try {
                out.put("ok", false).put("error", "bad json body: " + e.getMessage());
            } catch (JSONException ignored) {
            }
        }
        return out.toString();
    }

    /** The input-method focused node if editable, else the first editable
     *  node in the active window (focused ones preferred). */
    private AccessibilityNodeInfo findEditableTarget() {
        try {
            AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (focus != null && focus.isEditable()) {
                return focus;
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                AccessibilityNodeInfo found = findEditable(root);
                if (found != null) {
                    return found;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n) {
        if (n == null) {
            return null;
        }
        try {
            if (n.isEditable() && (n.isFocused() || n.getText() != null)) {
                return n;
            }
        } catch (Exception ignored) {
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findEditable(n.getChild(i));
            if (r != null) {
                return r;
            }
        }
        return null;
    }

    /** 取一个请求头的值（大小写不敏感），缺失返回空串。 */
    private static String headerValue(String[] lines, String name) {
        for (String h : lines) {
            int colon = h.indexOf(':');
            if (colon > 0 && h.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return h.substring(colon + 1).trim();
            }
        }
        return "";
    }

    /** 读掉并丢弃 len 字节请求体，避免 close 时发 RST 把响应冲掉。 */
    private static void drain(java.io.InputStream in, int len) {
        if (len <= 0) {
            return;
        }
        byte[] buf = new byte[len];
        int off = 0;
        try {
            while (off < buf.length) {
                int n = in.read(buf, off, buf.length - off);
                if (n < 0) {
                    break;
                }
                off += n;
            }
        } catch (IOException ignored) {
        }
    }

    private void respond(Socket s, String status, String type, String body)
            throws IOException {
        byte[] payload = body.getBytes("UTF-8");
        OutputStream out = s.getOutputStream();
        String head = "HTTP/1.1 " + status + "\r\n"
                + "Content-Type: " + type + "; charset=utf-8\r\n"
                + "Content-Length: " + payload.length + "\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(payload);
        out.flush();
    }

    private String nodesJson() {
        JSONArray arr = new JSONArray();
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                walk(root, arr);
            }
        } catch (Exception ignored) {
        }
        return arr.toString();
    }

    private void walk(AccessibilityNodeInfo n, JSONArray arr) {
        if (n == null) {
            return;
        }
        String text = cs(n.getText());
        String desc = cs(n.getContentDescription());
        // 密码/验证码框不采集文本（安全审查 2026-09-15 P0-2，与 agent-lite 同步）。
        // 节点保留（模型需要知道这里能输入），内容换成占位符。
        try {
            if (n.isPassword()) {
                text = "····（密码框，内容不采集）";
                desc = "";
            }
        } catch (Exception ignored) { }
        boolean clickable = n.isClickable();
        boolean scrollable = n.isScrollable();
        if (clickable || scrollable || !text.isEmpty() || !desc.isEmpty()) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            JSONObject o = new JSONObject();
            try {
                o.put("index", arr.length());
                o.put("text", text);
                o.put("desc", desc);
                o.put("id", cs(n.getViewIdResourceName()));
                o.put("class", shortName(n.getClassName()));
                o.put("clickable", clickable);
                o.put("scrollable", scrollable);
                o.put("cx", r.centerX());
                o.put("cy", r.centerY());
                arr.put(o);
            } catch (JSONException ignored) {
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            walk(n.getChild(i), arr);
        }
    }

    private static String cs(CharSequence c) {
        if (c == null) {
            return "";
        }
        return c.toString().trim();
    }

    private static String shortName(CharSequence cls) {
        if (cls == null) {
            return "";
        }
        String s = cls.toString();
        int i = s.lastIndexOf('.');
        return i >= 0 ? s.substring(i + 1) : s;
    }

    @Override
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
