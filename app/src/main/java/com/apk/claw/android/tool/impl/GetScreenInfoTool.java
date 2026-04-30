package com.apk.claw.android.tool.impl;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class GetScreenInfoTool extends BaseTool {

    @Override
    public String getName() {
        return "get_screen_info";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_get_screen_info);
    }

    @Override
    public String getDescriptionEN() {
        return "Get the current screen's UI hierarchy tree, including all visible elements with their properties (text, id, bounds, clickable, etc.). Use this to understand what is currently displayed on the screen.";
    }

    @Override
    public String getDescriptionCN() {
        return "获取当前屏幕的UI层级树，包括所有可见元素的属性（文本、ID、边界、可点击状态等）。用于了解当前屏幕显示的内容。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Collections.emptyList();
    }

    public static final String SYSTEM_DIALOG_BLOCKED = "__SYSTEM_DIALOG_BLOCKED__";

    /**
     * 切换为完整节点树模式（包含所有节点和全部属性，用于调试）。
     * false = 精简模式（默认，省 token）；true = 完整模式。
     */
    public static boolean useFullTree = false;

    private static final Pattern ELEMENT_PATTERN = Pattern.compile(
            "\\[(\\w+)\\] (?:text|desc)=\"([^\"]+)\".*?bounds=\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\]");

    @Override
    public ToolResult execute(Map<String, Object> params) {
        ClawAccessibilityService service = ClawAccessibilityService.getInstance();
        if (service == null) {
            return ToolResult.error("Accessibility service is not running");
        }
        String tree;
        if (useFullTree) {
            tree = service.getScreenTreeFull();
        } else {
            tree = service.getScreenTreeSmart();
        }
        if (tree == null) {
            return ToolResult.error(SYSTEM_DIALOG_BLOCKED);
        }

        // --- Long-term memory integration ---
        StringBuilder output = new StringBuilder();

        // Prepend known element positions for the current foreground app
        String fgPkg = service.getForegroundPackage();
        if (fgPkg != null && !fgPkg.isEmpty()) {
            String hints = ScreenMemory.recallHints(fgPkg);
            if (!hints.isEmpty()) {
                output.append(hints).append("\n\n");
            }

            // Auto-record: save key elements from this screen to memory
            autoRecord(fgPkg, tree);
        }

        output.append(tree);
        return ToolResult.success(output.toString());
    }

    /**
     * Extracts clickable / labelled elements from the screen tree and saves
     * them to ScreenMemory so future tasks can skip the initial scan.
     */
    private void autoRecord(String packageName, String tree) {
        if (tree == null || tree.length() < 10) return;
        Matcher m = ELEMENT_PATTERN.matcher(tree);
        int count = 0;
        while (m.find() && count < 15) {
            String klass = m.group(1);
            String text = m.group(2);
            if (text.isEmpty()) continue;
            String bounds = "[" + m.group(3) + "," + m.group(4) + "][" + m.group(5) + "," + m.group(6) + "]";
            // Determine clickable from context — the pattern captures the element line
            String line = m.group(0);
            boolean clickable = line.contains("[clickable]");

            ScreenMemory.record(packageName, text, bounds, klass, clickable,
                    clickable ? "" : "tap parent container");
            count++;
        }
    }
}
