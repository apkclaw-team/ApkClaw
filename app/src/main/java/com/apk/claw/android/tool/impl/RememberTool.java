package com.apk.claw.android.tool.impl;

import com.apk.claw.android.ClawApplication;
import com.apk.claw.android.R;
import com.apk.claw.android.tool.BaseTool;
import com.apk.claw.android.tool.ToolParameter;
import com.apk.claw.android.tool.ToolResult;
import com.apk.claw.android.utils.TaskMemory;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Lets the model persist and query cross-task memories.
 *
 * The model should call this tool:
 * - After a successful task: save learnings so future instances benefit
 * - Before tackling a complex task: search for relevant past experience
 *
 * Memories persist across app restarts and survive for unlimited time
 * (max 50 entries, oldest evicted).
 */
public class RememberTool extends BaseTool {

    @Override
    public String getName() {
        return "remember";
    }

    @Override
    public String getDisplayName() {
        return ClawApplication.Companion.getInstance().getString(R.string.tool_name_remember);
    }

    @Override
    public String getDescriptionEN() {
        return "Save or search long-term memories across tasks. " +
               "Use action='save' to record what you learned after a successful task. " +
               "Use action='search' to find relevant past experiences before starting. " +
               "Use action='list' to see all recent memories.";
    }

    @Override
    public String getDescriptionCN() {
        return "跨任务长期记忆。action='save' 记录经验，" +
               "action='search' 查找相关历史，" +
               "action='list' 列出最近记忆。";
    }

    @Override
    public List<ToolParameter> getParameters() {
        return Arrays.asList(
                new ToolParameter("action", "string",
                        "Action: 'save', 'search', or 'list'", true),
                new ToolParameter("task", "string",
                        "For 'save': one-line description of the user's task", false),
                new ToolParameter("summary", "string",
                        "For 'save': summary of what you did (key steps)", false),
                new ToolParameter("learnings", "string",
                        "For 'save': things you learned, separated by '|'", false),
                new ToolParameter("success", "boolean",
                        "For 'save': whether the task succeeded", false),
                new ToolParameter("keyword", "string",
                        "For 'search': keyword to search in memories", false)
        );
    }

    @Override
    public ToolResult execute(Map<String, Object> params) {
        String action = requireString(params, "action");

        switch (action.toLowerCase()) {
            case "save":
                return doSave(params);
            case "search":
                return doSearch(params);
            case "list":
                return doList();
            default:
                return ToolResult.error("Unknown action: " + action + ". Use 'save', 'search', or 'list'.");
        }
    }

    private ToolResult doSave(Map<String, Object> params) {
        String task = requireString(params, "task");
        String summary = requireString(params, "summary");

        TaskMemory.Entry entry = new TaskMemory.Entry();
        entry.userTask = task;
        entry.summary = summary;
        entry.success = params.containsKey("success") && Boolean.TRUE.equals(params.get("success"));

        String learningsRaw = params.containsKey("learnings") ? params.get("learnings").toString() : "";
        if (!learningsRaw.isEmpty()) {
            entry.learnings = Arrays.asList(learningsRaw.split("\\|"));
        } else {
            entry.learnings = Collections.emptyList();
        }

        TaskMemory.save(entry);
        return ToolResult.success("Memory saved (#" + entry.id + "). Total memories: " + TaskMemory.count());
    }

    private ToolResult doSearch(Map<String, Object> params) {
        String keyword = requireString(params, "keyword");
        String result = TaskMemory.search(keyword);
        return ToolResult.success(result);
    }

    private ToolResult doList() {
        String result = TaskMemory.recallForSystemPrompt();
        if (result.isEmpty()) {
            return ToolResult.success("[记忆] 暂无历史经验记录。");
        }
        return ToolResult.success(result);
    }
}
