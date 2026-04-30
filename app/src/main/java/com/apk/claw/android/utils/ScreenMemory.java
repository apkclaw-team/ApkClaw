package com.apk.claw.android.utils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.HashMap;
import java.util.Map;

/**
 * Long-term UI element memory backed by MMKV.
 *
 * Persists element positions across tasks so the model can skip expensive
 * screen scanning and trial-and-error the next time it visits the same app page.
 *
 * Keyed by {@code packageName → elementText}, each entry stores bounds, class,
 * clickable state, and a timestamp for staleness detection.
 */
public class ScreenMemory {

    private static final String TAG = "ScreenMemory";
    private static final String MMKV_KEY = "screen_memory";
    private static final long MAX_AGE_MS = 7 * 24 * 3600 * 1000L; // 7 days
    private static final Gson GSON = new Gson();

    /** In-memory cache, loaded from MMKV on first access */
    private static Map<String, Map<String, Entry>> store;

    // ======================== Public API ========================

    /**
     * Records an element in long-term memory.
     *
     * @param packageName  e.g. "com.tencent.mm"
     * @param text         the visible text / contentDescription
     * @param bounds       formatted bounds string "[left,top][right,bottom]"
     * @param className    e.g. "TextView", "ImageView", "LinearLayout"
     * @param clickable    whether the element itself is clickable
     * @param extraHint    additional info (parent clickable, viewId, etc.)
     */
    public static void record(String packageName, String text,
                               String bounds, String className, boolean clickable,
                               String extraHint) {
        if (packageName == null || text == null || text.isEmpty()) return;
        ensureLoaded();
        Map<String, Entry> pkg = store.computeIfAbsent(packageName, k -> new HashMap<>());
        String key = text.trim().toLowerCase();
        Entry e = new Entry();
        e.bounds = bounds;
        e.className = className;
        e.clickable = clickable;
        e.extraHint = extraHint != null ? extraHint : "";
        e.timestamp = System.currentTimeMillis();
        pkg.put(key, e);
        XLog.d(TAG, "Recorded [" + text + "] in " + packageName + " at " + bounds);
        persistAsync();
    }

    /**
     * Looks up a single element from memory.
     *
     * @return Entry if found and not stale, null otherwise
     */
    public static Entry recall(String packageName, String text) {
        if (packageName == null || text == null) return null;
        ensureLoaded();
        Map<String, Entry> pkg = store.get(packageName);
        if (pkg == null) return null;
        Entry e = pkg.get(text.trim().toLowerCase());
        if (e == null) return null;
        if (isStale(e)) {
            pkg.remove(text.trim().toLowerCase());
            persistAsync();
            return null;
        }
        return e;
    }

    /**
     * Returns a human-readable hint string of all remembered elements
     * for the given package. Intended to be prepended to screen-info output.
     *
     * @return formatted hint string, or empty string if no memories
     */
    public static String recallHints(String packageName) {
        if (packageName == null) return "";
        ensureLoaded();
        Map<String, Entry> pkg = store.get(packageName);
        if (pkg == null || pkg.isEmpty()) return "";

        // Remove stale entries while building output
        StringBuilder sb = new StringBuilder();
        sb.append("[记忆 — 该 App 已知元素位置]\n");
        int count = 0;
        for (Map.Entry<String, Entry> kv : pkg.entrySet()) {
            if (isStale(kv.getValue())) continue;
            if (count > 0) sb.append("\n");
            sb.append("- ").append(kv.getKey())
              .append(": bounds=").append(kv.getValue().bounds);
            if (!kv.getValue().clickable) {
                sb.append(" ⚠非clickable,需tap父容器");
            }
            count++;
            if (count >= 20) break; // limit
        }
        return count > 0 ? sb.toString() : "";
    }

    /**
     * Explicitly remove a stale entry.
     */
    public static void forget(String packageName, String text) {
        if (packageName == null || text == null) return;
        ensureLoaded();
        Map<String, Entry> pkg = store.get(packageName);
        if (pkg != null) {
            pkg.remove(text.trim().toLowerCase());
            persistAsync();
        }
    }

    /**
     * Clear all memories for a package.
     */
    public static void clearPackage(String packageName) {
        ensureLoaded();
        store.remove(packageName);
        persistAsync();
    }

    // ======================== Entry ========================

    public static class Entry {
        public String bounds;
        public String className;
        public boolean clickable;
        public String extraHint;
        public long timestamp;

        public String centerXY() {
            if (bounds == null) return "?";
            // bounds format: "[left,top][right,bottom]" or "[left,top][right,bottom]"
            try {
                int b1 = bounds.indexOf('[');
                int c1 = bounds.indexOf(',', b1);
                int b2 = bounds.indexOf("][");
                int c2 = bounds.indexOf(',', b2);
                int b3 = bounds.lastIndexOf(']');
                int left = Integer.parseInt(bounds.substring(b1 + 1, c1).trim());
                int top = Integer.parseInt(bounds.substring(c1 + 1, b2).trim());
                int right = Integer.parseInt(bounds.substring(b2 + 2, c2).trim());
                int bottom = Integer.parseInt(bounds.substring(c2 + 1, b3).trim());
                return (left + right) / 2 + "," + (top + bottom) / 2;
            } catch (Exception e) {
                return "?";
            }
        }
    }

    // ======================== Internal ========================

    private static void ensureLoaded() {
        if (store != null) return;
        store = new HashMap<>();
        String raw = KVUtils.INSTANCE.getString(MMKV_KEY, "");
        if (raw.isEmpty()) return;
        try {
            JsonObject root = JsonParser.parseString(raw).getAsJsonObject();
            for (Map.Entry<String, JsonElement> pkgEntry : root.entrySet()) {
                Map<String, Entry> pkg = new HashMap<>();
                JsonObject pkgJson = pkgEntry.getValue().getAsJsonObject();
                for (Map.Entry<String, JsonElement> elemEntry : pkgJson.entrySet()) {
                    Entry e = GSON.fromJson(elemEntry.getValue(), Entry.class);
                    pkg.put(elemEntry.getKey(), e);
                }
                store.put(pkgEntry.getKey(), pkg);
            }
        } catch (Exception e) {
            XLog.w(TAG, "Failed to parse screen memory, starting fresh", e);
            store.clear();
        }
    }

    private static void persistAsync() {
        if (store == null) return;
        // Prune stale entries while serializing
        JsonObject root = new JsonObject();
        for (Map.Entry<String, Map<String, Entry>> pkgEntry : store.entrySet()) {
            JsonObject pkgJson = new JsonObject();
            for (Map.Entry<String, Entry> elemEntry : pkgEntry.getValue().entrySet()) {
                if (!isStale(elemEntry.getValue())) {
                    pkgJson.add(elemEntry.getKey(), GSON.toJsonTree(elemEntry.getValue()));
                }
            }
            if (pkgJson.size() > 0) {
                root.add(pkgEntry.getKey(), pkgJson);
            }
        }
        KVUtils.INSTANCE.putString(MMKV_KEY, root.toString());
    }

    private static boolean isStale(Entry e) {
        return System.currentTimeMillis() - e.timestamp > MAX_AGE_MS;
    }
}
