package com.apk.claw.android.tool.impl;

import android.view.accessibility.AccessibilityNodeInfo;

import com.apk.claw.android.ClawApplication;
import com.apk.claw.android.R;
import com.apk.claw.android.service.ClawAccessibilityService;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;
import com.apk.claw.android.utils.ScreenMemory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class FindNodeInfoTool extends BaseTool {

    @Override
    public String getName() {
        return "find_node_info";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_find_node_info);
    }

    @Override
    public String getDescriptionEN() {
        return "Find elements by visible text and return their detailed information (class, bounds, properties). Useful for inspecting specific elements before interacting.";
    }

    @Override
    public String getDescriptionCN() {
        return "通过可见文本查找元素，返回详细信息（类名、边界、属性）。适用于在交互前检查特定元素。会在长期记忆中缓存结果，下次查找优先命中缓存。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.singletonList(
                new ToolParameter("text", "string", "The visible text to search for", true)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }

        String text = requireString(params, "text");
        String fgPkg = service.getForegroundPackage();

        // --- Check long-term memory first ---
        if (fgPkg != null && !fgPkg.isEmpty()) {
            ScreenMemory.Entry mem = ScreenMemory.recall(fgPkg, text);
            if (mem != null) {
                // Cache hit — return immediately, then verify with scan below
                // We'll prepend the memory hint but still do a live scan for accuracy
            }
        }

        // Use anti-obfuscation search when WeChat is in foreground
        List<AccessibilityNodeInfo> nodes;
        if (service.isAntiA11yAppForeground()) {
            nodes = service.findNodesByTextAntiObfuscated(text);
        } else {
            nodes = service.findNodesByText(text);
        }

        if (nodes.isEmpty()) {
            return ToolResult.error("No elements found with text: " + text
                    + (service.isAntiA11yAppForeground() ? " (anti-obfuscation search used)" : ""));
        }

        try {
            StringBuilder sb = new StringBuilder();

            // Prepend memory hint if available for the foreground app
            if (fgPkg != null) {
                ScreenMemory.Entry mem = ScreenMemory.recall(fgPkg, text);
                if (mem != null) {
                    sb.append("[记忆缓存命中] 之前找到过该元素:\n")
                      .append("  bounds=").append(mem.bounds)
                      .append("  class=").append(mem.className)
                      .append("  clickable=").append(mem.clickable)
                      .append(mem.clickable ? "" : " (需tap父容器)")
                      .append("\n  center_xy=").append(mem.centerXY())
                      .append("\n当前扫描结果如下:\n\n");
                }
            }

            sb.append("Found ").append(nodes.size()).append(" element(s):\n");
            for (int i = 0; i < nodes.size(); i++) {
                android.view.accessibility.AccessibilityNodeInfo node = nodes.get(i);
                String detail = service.getNodeDetail(node);
                sb.append("[").append(i).append("] ").append(detail).append("\n");

                // Save to memory: persist successful finds
                if (fgPkg != null && detail != null && !detail.isEmpty()) {
                    recordToMemory(fgPkg, text, node, detail);
                }
            }
            return ToolResult.success(sb.toString());
        } finally {
            ClawAccessibilityService.recycleNodes(nodes);
        }
    }

    private void recordToMemory(String pkg, String text,
                                 android.view.accessibility.AccessibilityNodeInfo node,
                                 String detail) {
        try {
            android.graphics.Rect bounds = new android.graphics.Rect();
            node.getBoundsInScreen(bounds);
            String boundsStr = "[" + bounds.left + "," + bounds.top + "]["
                    + bounds.right + "," + bounds.bottom + "]";
            boolean clickable = node.isClickable();
            String klass = node.getClassName() != null ? node.getClassName().toString() : "?";
            // Extract short class name
            int dot = klass.lastIndexOf('.');
            if (dot >= 0 && dot < klass.length() - 1) klass = klass.substring(dot + 1);

            StringBuilder hint = new StringBuilder();
            if (!clickable) hint.append("tap parent");
            String viewId = node.getViewIdResourceName();
            if (viewId != null && !viewId.isEmpty()) {
                if (hint.length() > 0) hint.append("; ");
                hint.append("id=").append(viewId);
            }

            ScreenMemory.record(pkg, text, boundsStr, klass, clickable, hint.toString());
        } catch (Exception ignored) {
        }
    }
}
