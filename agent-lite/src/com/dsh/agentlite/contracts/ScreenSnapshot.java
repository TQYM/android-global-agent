// SPDX-License-Identifier: Apache-2.0

package com.dsh.agentlite.contracts;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 脱离 AccessibilityNodeInfo 的屏幕语义快照契约。
 * 文本只在 password 节点上强制掩码；采集入口必须在构造前完成其它隐私策略。
 */
public final class ScreenSnapshot {
    public static final String PASSWORD_MASK = "[password]";

    public final int schemaVersion;
    public final long timestampMs;
    public final String packageName;
    public final String activityName;
    public final int displayId;
    public final int screenWidth;
    public final int screenHeight;
    public final boolean keyboardVisible;
    public final boolean masked;
    public final List<Element> elements;

    public ScreenSnapshot(int schemaVersion, long timestampMs, String packageName,
                          String activityName, int displayId, int screenWidth,
                          int screenHeight, boolean keyboardVisible, boolean masked,
                          List<Element> elements) {
        this.schemaVersion = schemaVersion;
        this.timestampMs = timestampMs;
        this.packageName = safe(packageName);
        this.activityName = safe(activityName);
        this.displayId = displayId;
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.keyboardVisible = keyboardVisible;
        this.masked = masked;
        this.elements = Collections.unmodifiableList(new ArrayList<>(
                elements == null ? Collections.<Element>emptyList() : elements));
    }

    public String toJson() {
        List<String> rendered = new ArrayList<>();
        for (Element element : elements) rendered.add(element.toJson());
        return ContractJson.object(
                ContractJson.intField("schema_version", schemaVersion),
                ContractJson.longField("timestamp_ms", timestampMs),
                ContractJson.stringField("package_name", packageName),
                ContractJson.stringField("activity_name", activityName),
                ContractJson.intField("display_id", displayId),
                ContractJson.intField("screen_width", screenWidth),
                ContractJson.intField("screen_height", screenHeight),
                ContractJson.booleanField("keyboard_visible", keyboardVisible),
                ContractJson.booleanField("masked", masked),
                ContractJson.quote("elements") + ":" + ContractJson.array(rendered));
    }

    private static String safe(String value) { return value == null ? "" : value; }

    public static final class Element {
        public final int index;
        public final String text;
        public final String description;
        public final String hintText;
        public final String resourceId;
        public final String className;
        public final boolean clickable;
        public final boolean enabled;
        public final boolean checked;
        public final boolean scrollable;
        public final boolean password;
        public final Bounds bounds;
        public final String source;

        public Element(int index, String text, String description, String hintText,
                       String resourceId, String className, boolean clickable,
                       boolean enabled, boolean checked, boolean scrollable,
                       boolean password, Bounds bounds, String source) {
            this.index = index;
            this.password = password;
            this.text = password ? PASSWORD_MASK : safe(text);
            this.description = password ? PASSWORD_MASK : safe(description);
            this.hintText = password ? PASSWORD_MASK : safe(hintText);
            this.resourceId = safe(resourceId);
            this.className = safe(className);
            this.clickable = clickable;
            this.enabled = enabled;
            this.checked = checked;
            this.scrollable = scrollable;
            this.bounds = bounds == null ? new Bounds(0, 0, 0, 0) : bounds;
            this.source = safe(source);
        }

        public String toJson() {
            return ContractJson.object(
                    ContractJson.intField("index", index),
                    ContractJson.stringField("text", text),
                    ContractJson.stringField("description", description),
                    ContractJson.stringField("hint_text", hintText),
                    ContractJson.stringField("resource_id", resourceId),
                    ContractJson.stringField("class_name", className),
                    ContractJson.booleanField("clickable", clickable),
                    ContractJson.booleanField("enabled", enabled),
                    ContractJson.booleanField("checked", checked),
                    ContractJson.booleanField("scrollable", scrollable),
                    ContractJson.booleanField("password", password),
                    ContractJson.stringField("source", source),
                    ContractJson.quote("bounds") + ":" + bounds.toJson());
        }
    }

    public static final class Bounds {
        public final int left, top, right, bottom;

        public Bounds(int left, int top, int right, int bottom) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public String toJson() {
            return ContractJson.object(
                    ContractJson.intField("left", left),
                    ContractJson.intField("top", top),
                    ContractJson.intField("right", right),
                    ContractJson.intField("bottom", bottom));
        }
    }
}
