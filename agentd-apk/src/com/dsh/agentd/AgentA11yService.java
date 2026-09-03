package com.dsh.agentd;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Resident perception service: holds a first-class accessibility connection
 * (unlike the flaky uiautomator instrumentation bridge) and serves the
 * interactive-node tree as JSON on 127.0.0.1:8081 for agentd to consume.
 * Localhost only — nothing leaves the device.
 */
public class AgentA11yService extends AccessibilityService {

    private static final int PORT = 8081;
    private Thread serverThread;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
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

    private void serve() {
        try {
            ServerSocket ss = new ServerSocket(PORT);
            while (true) {
                try {
                    Socket s = ss.accept();
                    handle(s);
                } catch (IOException ignored) {
                }
            }
        } catch (IOException e) {
            // port busy or fatal — the next service reconnect retries
        }
    }

    private void handle(Socket s) {
        try {
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), "UTF-8"));
            String requestLine = in.readLine();
            if (requestLine == null) {
                s.close();
                return;
            }
            if (requestLine.startsWith("GET /nodes")) {
                respond(s, "200 OK", "application/json", nodesJson());
            } else {
                respond(s, "404 Not Found", "text/plain", "not found");
            }
            s.close();
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
