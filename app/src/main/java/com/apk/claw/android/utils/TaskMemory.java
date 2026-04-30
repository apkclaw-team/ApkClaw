package com.apk.claw.android.utils;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Cross-task conversation memory.
 *
 * Persists summaries of successful tasks and the model's own learnings so
 * that future tasks can benefit from past experience instead of starting
 * from scratch every time.
 *
 * Memory entries are stored in MMKV under the key {@code task_memory} as a
 * JSON array, with a maximum of 50 entries (oldest evicted).
 */
public class TaskMemory {

    private static final String TAG = "TaskMemory";
    private static final String MMKV_KEY = "task_memory";
    private static final int MAX_ENTRIES = 100;
    private static final Gson GSON = new Gson();

    /** In-memory cache, loaded lazily */
    private static List<Entry> store;

    // ======================== Entry ========================

    public static class Entry {
        public int id;
        /** One-line description of what the user asked for */
        public String userTask;
        /** Whether the task completed successfully */
        public boolean success;
        /** Short summary of what the model did (key steps) */
        public String summary;
        /** Things the model learned — reusable across tasks */
        public List<String> learnings;
        public long timestamp;

        public String toPromptString() {
            StringBuilder sb = new StringBuilder();
            sb.append("任务: ").append(userTask != null ? userTask : "?").append("\n");
            sb.append("结果: ").append(success ? "成功" : "失败").append("\n");
            if (summary != null && !summary.isEmpty()) {
                sb.append("过程: ").append(summary).append("\n");
            }
            if (learnings != null && !learnings.isEmpty()) {
                sb.append("经验:\n");
                for (String l : learnings) {
                    sb.append("  - ").append(l).append("\n");
                }
            }
            return sb.toString();
        }
    }

    // ======================== Public API ========================

    /**
     * Saves a new memory entry. The model should call this via the
     * "remember" tool when it learns something worth keeping.
     */
    public static void save(Entry entry) {
        ensureLoaded();
        entry.id = nextId();
        if (entry.timestamp == 0) entry.timestamp = System.currentTimeMillis();
        if (entry.learnings == null) entry.learnings = Collections.emptyList();
        store.add(entry);
        XLog.d(TAG, "Saved memory #" + entry.id + ": " + (entry.userTask != null ? entry.userTask : "?"));
        trimAndPersist();
    }

    /**
     * Returns all memories as a formatted string suitable for injection
     * into the system prompt. Most recent first, up to 10 entries.
     */
    public static String recallForSystemPrompt() {
        ensureLoaded();
        if (store.isEmpty()) return "";

        // Sort most recent first
        List<Entry> sorted = new ArrayList<>(store);
        sorted.sort(Comparator.comparingLong((Entry e) -> e.timestamp).reversed());

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n## 长期记忆（跨任务经验）\n");
        sb.append("以下是你过去执行任务的经验记录。参考它们可以加快任务完成速度、避免重复错误。\n\n");

        int limit = Math.min(10, sorted.size());
        for (int i = 0; i < limit; i++) {
            sb.append("### 记忆 #").append(i + 1).append("\n");
            sb.append(sorted.get(i).toPromptString()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Searches memories by keyword (simple case-insensitive text match on
     * userTask + summary + learnings). Most recent first.
     */
    public static String search(String keyword) {
        ensureLoaded();
        if (keyword == null || keyword.isEmpty()) return recallForSystemPrompt();

        String lower = keyword.toLowerCase().trim();
        List<Entry> matches = new ArrayList<>();
        for (Entry e : store) {
            StringBuilder haystack = new StringBuilder();
            if (e.userTask != null) haystack.append(e.userTask).append(" ");
            if (e.summary != null) haystack.append(e.summary).append(" ");
            if (e.learnings != null) {
                for (String l : e.learnings) haystack.append(l).append(" ");
            }
            if (haystack.toString().toLowerCase().contains(lower)) {
                matches.add(e);
            }
        }
        if (matches.isEmpty()) return "[记忆] 没有找到与 \"" + keyword + "\" 相关的历史经验。";

        matches.sort(Comparator.comparingLong((Entry e) -> e.timestamp).reversed());

        int limit = Math.min(5, matches.size());
        StringBuilder sb = new StringBuilder();
        sb.append("[记忆] 找到 ").append(matches.size()).append(" 条相关经验:\n\n");
        for (int i = 0; i < limit; i++) {
            sb.append("--- #").append(i + 1).append(" ---\n");
            sb.append(matches.get(i).toPromptString()).append("\n");
        }
        return sb.toString();
    }

    /**
     * Returns the count of stored memories.
     */
    public static int count() {
        ensureLoaded();
        return store.size();
    }

    /**
     * Clears all memories.
     */
    public static void clear() {
        store = new ArrayList<>();
        KVUtils.INSTANCE.putString(MMKV_KEY, "[]");
    }

    // ======================== Internal ========================

    private static void ensureLoaded() {
        if (store != null) return;
        store = new ArrayList<>();
        String raw = KVUtils.INSTANCE.getString(MMKV_KEY, "");
        if (raw.isEmpty()) return;
        try {
            JsonArray arr = JsonParser.parseString(raw).getAsJsonArray();
            for (JsonElement elem : arr) {
                Entry e = GSON.fromJson(elem, Entry.class);
                if (e != null) store.add(e);
            }
        } catch (Exception e) {
            XLog.w(TAG, "Failed to parse task memory, starting fresh", e);
            store.clear();
        }
    }

    private static int nextId() {
        int max = 0;
        for (Entry e : store) {
            if (e.id > max) max = e.id;
        }
        return max + 1;
    }

    private static void trimAndPersist() {
        // Keep only the most recent MAX_ENTRIES
        if (store.size() > MAX_ENTRIES) {
            store.sort(Comparator.comparingLong((Entry e) -> e.timestamp).reversed());
            while (store.size() > MAX_ENTRIES) {
                store.remove(store.size() - 1);
            }
        }
        String json = GSON.toJson(store);
        KVUtils.INSTANCE.putString(MMKV_KEY, json);
    }
}
