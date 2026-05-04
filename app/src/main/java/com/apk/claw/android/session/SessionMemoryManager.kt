package com.apk.claw.android.session

import com.apk.claw.android.utils.KVUtils
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class SessionChatMessage(
    val id: String,
    val role: String,
    var content: String,
    val timestamp: Long
)

data class SessionMemory(
    val id: String,
    var name: String,
    val createdAt: Long,
    var updatedAt: Long,
    val messages: MutableList<SessionChatMessage> = mutableListOf(),
    var sessionTranscript: String = "",
    var condensedSummary: String = "",
    var habitNotes: String = "",
    var sessionPrompt: String = "",
    val recentTasks: MutableList<String> = mutableListOf(),
    val successfulTaskCounts: MutableMap<String, Int> = mutableMapOf(),
    val errorLessons: MutableList<String> = mutableListOf()
)

data class GlobalMemory(
    var memoryText: String = "",
    var promptText: String = ""
)

data class SessionMemoryState(
    var sessionEnabled: Boolean = false,
    var globalMemoryEnabled: Boolean = false,
    var globalPromptEnabled: Boolean = false,
    var currentSessionId: String = "",
    val globalMemory: GlobalMemory = GlobalMemory(),
    val sessions: MutableList<SessionMemory> = mutableListOf()
)

object SessionMemoryManager {

    const val ROLE_USER = "user"
    const val ROLE_ASSISTANT = "assistant"
    const val ROLE_SYSTEM = "system"

    const val FIELD_CONDENSED_SUMMARY = "condensed_summary"
    const val FIELD_HABIT_NOTES = "habit_notes"
    const val FIELD_SESSION_PROMPT = "session_prompt"
    const val FIELD_GLOBAL_MEMORY = "global_memory"
    const val FIELD_GLOBAL_PROMPT = "global_prompt"

    private const val KEY_SESSION_MEMORY_STATE = "KEY_SESSION_MEMORY_STATE"
    private const val DEFAULT_SESSION_NAME = "默认会话"
    private const val MAX_SESSION_TRANSCRIPT_CHARS = 1500
    private const val MAX_PROMPT_BLOCK_CHARS = 1200

    private val gson = Gson()
    private val stateType = object : TypeToken<SessionMemoryState>() {}.type
    private val dayFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())

    @Synchronized
    fun isSessionEnabled(): Boolean = loadState().sessionEnabled

    @Synchronized
    fun setSessionEnabled(enabled: Boolean) {
        val state = loadState()
        state.sessionEnabled = enabled
        saveState(state)
    }

    @Synchronized
    fun isMemoryEnabled(): Boolean = loadState().globalMemoryEnabled

    @Synchronized
    fun setMemoryEnabled(enabled: Boolean) {
        val state = loadState()
        state.globalMemoryEnabled = enabled
        saveState(state)
    }

    @Synchronized
    fun isGlobalPromptEnabled(): Boolean = loadState().globalPromptEnabled

    @Synchronized
    fun setGlobalPromptEnabled(enabled: Boolean) {
        val state = loadState()
        state.globalPromptEnabled = enabled
        saveState(state)
    }

    @Synchronized
    fun listSessions(): List<SessionMemory> = loadState().sessions.sortedByDescending { it.updatedAt }

    @Synchronized
    fun getCurrentSession(): SessionMemory? {
        val state = loadState()
        return state.sessions.firstOrNull { it.id == state.currentSessionId }
    }

    @Synchronized
    fun getSession(sessionId: String): SessionMemory? {
        val state = loadState()
        return state.sessions.firstOrNull { it.id == sessionId }
    }

    @Synchronized
    fun getSessionMessages(sessionId: String): List<SessionChatMessage> {
        return getSession(sessionId)?.messages?.sortedBy { it.timestamp } ?: emptyList()
    }

    @Synchronized
    fun getCurrentSessionId(): String = loadState().currentSessionId

    @Synchronized
    fun setCurrentSession(sessionId: String): Boolean {
        val state = loadState()
        if (state.sessions.none { it.id == sessionId }) return false
        state.currentSessionId = sessionId
        saveState(state)
        return true
    }

    @Synchronized
    fun createSession(name: String): SessionMemory {
        val state = loadState()
        val now = System.currentTimeMillis()
        val session = SessionMemory(
            id = "session-${UUID.randomUUID().toString().substring(0, 8)}",
            name = name.ifBlank { "会话 ${state.sessions.size + 1}" },
            createdAt = now,
            updatedAt = now
        )
        state.sessions.add(0, session)
        state.currentSessionId = session.id
        saveState(state)
        return session
    }

    @Synchronized
    fun deleteSession(sessionId: String): Boolean {
        val state = loadState()
        val removed = state.sessions.removeAll { it.id == sessionId }
        if (!removed) return false
        val normalized = ensureState(state)
        saveState(normalized)
        return true
    }

    @Synchronized
    fun renameSession(sessionId: String, name: String): Boolean {
        val state = loadState()
        val session = state.sessions.firstOrNull { it.id == sessionId } ?: return false
        session.name = name.ifBlank { session.name }
        session.updatedAt = System.currentTimeMillis()
        saveState(state)
        return true
    }

    @Synchronized
    fun appendSessionMessage(sessionId: String, role: String, content: String): Boolean {
        val normalized = content.trim()
        if (normalized.isEmpty()) return false
        val state = loadState()
        val session = state.sessions.firstOrNull { it.id == sessionId } ?: return false
        session.messages += SessionChatMessage(
            id = "msg-${UUID.randomUUID().toString().substring(0, 8)}",
            role = role,
            content = normalized,
            timestamp = System.currentTimeMillis()
        )
        session.sessionTranscript = buildTranscriptFromMessages(session.messages)
        session.updatedAt = System.currentTimeMillis()
        saveState(state)
        return true
    }

    @Synchronized
    fun getMemoryText(sessionId: String, field: String): String {
        val state = loadState()
        return when (field) {
            FIELD_CONDENSED_SUMMARY -> state.sessions.firstOrNull { it.id == sessionId }?.condensedSummary.orEmpty()
            FIELD_HABIT_NOTES -> state.sessions.firstOrNull { it.id == sessionId }?.habitNotes.orEmpty()
            FIELD_SESSION_PROMPT -> state.sessions.firstOrNull { it.id == sessionId }?.sessionPrompt.orEmpty()
            FIELD_GLOBAL_MEMORY -> state.globalMemory.memoryText
            FIELD_GLOBAL_PROMPT -> state.globalMemory.promptText
            else -> ""
        }
    }

    @Synchronized
    fun updateMemoryText(sessionId: String, field: String, value: String): Boolean {
        val state = loadState()
        when (field) {
            FIELD_CONDENSED_SUMMARY -> {
                val session = state.sessions.firstOrNull { it.id == sessionId } ?: return false
                session.condensedSummary = value.trim()
                session.updatedAt = System.currentTimeMillis()
            }
            FIELD_HABIT_NOTES -> {
                val session = state.sessions.firstOrNull { it.id == sessionId } ?: return false
                session.habitNotes = value.trim()
                session.updatedAt = System.currentTimeMillis()
            }
            FIELD_SESSION_PROMPT -> {
                val session = state.sessions.firstOrNull { it.id == sessionId } ?: return false
                session.sessionPrompt = value.trim()
                session.updatedAt = System.currentTimeMillis()
            }
            FIELD_GLOBAL_MEMORY -> state.globalMemory.memoryText = value.trim()
            FIELD_GLOBAL_PROMPT -> state.globalMemory.promptText = value.trim()
            else -> return false
        }
        saveState(state)
        return true
    }

    @Synchronized
    fun updateSessionContent(
        sessionId: String,
        name: String,
        sessionTranscript: String,
        condensedSummary: String,
        habitNotes: List<String>,
        errorLessons: List<String>
    ): Boolean {
        val state = loadState()
        val session = state.sessions.firstOrNull { it.id == sessionId } ?: return false
        session.name = name.ifBlank { session.name }
        session.sessionTranscript = trimTranscript(sessionTranscript)
        session.condensedSummary = condensedSummary.trim()
        session.habitNotes = habitNotes.joinToString("\n")
        session.sessionPrompt = errorLessons.joinToString("\n")
        session.updatedAt = System.currentTimeMillis()
        saveState(state)
        return true
    }

    @Synchronized
    fun writeSessionFieldToGlobalMemory(sessionId: String, field: String, overrideValue: String? = null): Boolean {
        val state = loadState()
        val session = state.sessions.firstOrNull { it.id == sessionId } ?: return false
        val source = overrideValue?.trim().orEmpty().ifBlank {
            when (field) {
                FIELD_CONDENSED_SUMMARY -> session.condensedSummary
                FIELD_HABIT_NOTES -> session.habitNotes
                else -> ""
            }
        }.trim()
        if (source.isBlank()) return false
        val title = when (field) {
            FIELD_CONDENSED_SUMMARY -> "会话凝练记忆"
            FIELD_HABIT_NOTES -> "会话习惯偏好"
            else -> return false
        }
        state.globalMemory.memoryText = appendDatedSection(
            current = state.globalMemory.memoryText,
            date = todayTag(),
            header = "$title · ${session.name}",
            body = source
        )
        saveState(state)
        return true
    }

    @Synchronized
    fun buildTaskPrompt(userTask: String): String {
        return buildTaskPrompt(loadState().currentSessionId, userTask)
    }

    @Synchronized
    fun buildTaskPrompt(sessionId: String, userTask: String): String {
        val state = loadState()
        val session = state.sessions.firstOrNull { it.id == sessionId } ?: return userTask

        val sections = mutableListOf<String>()
        if (state.sessionEnabled && session.sessionTranscript.isNotBlank()) {
            buildPromptSection(
                title = "当前会话原始记录（已压缩）",
                content = session.sessionTranscript,
                limit = MAX_SESSION_TRANSCRIPT_CHARS
            )?.let { sections += it }
        }
        if (session.sessionPrompt.isNotBlank()) {
            buildPromptSection(
                title = "会话Prompt",
                content = session.sessionPrompt,
                limit = MAX_PROMPT_BLOCK_CHARS
            )?.let { sections += it }
        }
        if (session.condensedSummary.isNotBlank()) {
            buildPromptSection(
                title = "会话凝练记忆",
                content = session.condensedSummary,
                limit = MAX_PROMPT_BLOCK_CHARS
            )?.let { sections += it }
        }
        if (session.habitNotes.isNotBlank()) {
            buildPromptSection(
                title = "会话习惯/偏好",
                content = session.habitNotes,
                limit = MAX_PROMPT_BLOCK_CHARS
            )?.let { sections += it }
        }
        if (state.globalMemoryEnabled && state.globalMemory.memoryText.isNotBlank()) {
            buildPromptSection(
                title = "全局记忆",
                content = state.globalMemory.memoryText,
                limit = MAX_PROMPT_BLOCK_CHARS
            )?.let { sections += it }
        }
        if (state.globalPromptEnabled && state.globalMemory.promptText.isNotBlank()) {
            buildPromptSection(
                title = "全局Prompt",
                content = state.globalMemory.promptText,
                limit = MAX_PROMPT_BLOCK_CHARS
            )?.let { sections += it }
        }

        if (sections.isEmpty()) return userTask

        return buildString {
            append("## 当前会话上下文\n")
            append("- 会话名: ").append(session.name).append("\n")
            append("- 历史会话里如果出现旧的 wait_after 数值、等待习惯或缩放说明，全部视为过期；当前任务必须以当前系统提示词里的等待建议为准。\n")
            append(sections.joinToString("\n"))
            append("\n- 以上内容仅作为参考；如果与当前用户指令冲突，以当前指令为准。\n\n")
            append("## 当前用户任务\n")
            append(userTask)
        }
    }

    @Synchronized
    fun updateCurrentSessionTranscriptSnapshot(transcript: String): Boolean {
        return updateSessionTranscriptSnapshot(loadState().currentSessionId, transcript)
    }

    @Synchronized
    fun updateSessionTranscriptSnapshot(sessionId: String, transcript: String): Boolean {
        val state = loadState()
        if (!state.sessionEnabled) return false
        val session = state.sessions.firstOrNull { it.id == sessionId } ?: return false
        session.sessionTranscript = deriveTranscript(session, transcript)
        session.updatedAt = System.currentTimeMillis()
        saveState(state)
        return true
    }

    @Synchronized
    fun recordSuccess(userTask: String, sessionTranscript: String, finalSummary: String) {
        recordSuccess(loadState().currentSessionId, userTask, sessionTranscript, finalSummary)
    }

    @Synchronized
    fun recordSuccess(sessionId: String, userTask: String, sessionTranscript: String, finalSummary: String) {
        val state = loadState()
        val session = state.sessions.firstOrNull { it.id == sessionId } ?: return
        val normalizedTask = normalizeTask(userTask)
        if (state.sessionEnabled) {
            session.sessionTranscript = deriveTranscript(session, sessionTranscript)
        }
        if (finalSummary.isNotBlank()) {
            session.condensedSummary = appendDatedBullet(session.condensedSummary, todayTag(), sanitizeLine(finalSummary))
        }
        val count = (session.successfulTaskCounts[normalizedTask] ?: 0) + 1
        session.successfulTaskCounts[normalizedTask] = count
        if (count >= 2) {
            session.habitNotes = appendDatedBullet(session.habitNotes, todayTag(), "高频任务偏好：$normalizedTask")
        }
        session.updatedAt = System.currentTimeMillis()
        saveState(state)
    }

    @Synchronized
    fun recordFailure(userTask: String, errorMessage: String, sessionTranscript: String) {
        recordFailure(loadState().currentSessionId, userTask, errorMessage, sessionTranscript)
    }

    @Synchronized
    fun recordFailure(sessionId: String, userTask: String, errorMessage: String, sessionTranscript: String) {
        val state = loadState()
        val session = state.sessions.firstOrNull { it.id == sessionId } ?: return
        val normalizedTask = normalizeTask(userTask)
        if (state.sessionEnabled) {
            session.sessionTranscript = deriveTranscript(session, sessionTranscript)
        }
        session.condensedSummary = appendDatedBullet(
            session.condensedSummary,
            todayTag(),
            "任务“$normalizedTask”失败：${sanitizeLine(errorMessage)}"
        )
        session.updatedAt = System.currentTimeMillis()
        saveState(state)
    }

    @Synchronized
    fun recordCancellation(userTask: String, reason: String, sessionTranscript: String) {
        recordCancellation(loadState().currentSessionId, userTask, reason, sessionTranscript)
    }

    @Synchronized
    fun recordCancellation(sessionId: String, userTask: String, reason: String, sessionTranscript: String) {
        val state = loadState()
        val session = state.sessions.firstOrNull { it.id == sessionId } ?: return
        val normalizedTask = normalizeTask(userTask)
        if (state.sessionEnabled) {
            session.sessionTranscript = deriveTranscript(session, sessionTranscript)
        }
        session.condensedSummary = appendDatedBullet(
            session.condensedSummary,
            todayTag(),
            "任务“$normalizedTask”已取消：${sanitizeLine(reason)}"
        )
        session.updatedAt = System.currentTimeMillis()
        saveState(state)
    }

    @Synchronized
    fun getStatusSummary(): String {
        val state = loadState()
        val enabledCount = listOf(state.sessionEnabled, state.globalMemoryEnabled, state.globalPromptEnabled).count { it }
        return when (enabledCount) {
            0 -> "全部关闭"
            3 -> "全部开启"
            else -> "已开 $enabledCount 项"
        }
    }

    private fun loadState(): SessionMemoryState {
        val raw = KVUtils.getString(KEY_SESSION_MEMORY_STATE, "")
        val state = if (raw.isBlank()) {
            SessionMemoryState()
        } else {
            runCatching { gson.fromJson<SessionMemoryState>(normalizeStateJson(raw), stateType) }
                .getOrDefault(SessionMemoryState())
        }
        return ensureState(state)
    }

    private fun ensureState(state: SessionMemoryState): SessionMemoryState {
        if (state.sessions.isEmpty()) {
            val now = System.currentTimeMillis()
            state.sessions += SessionMemory(
                id = "session-default",
                name = DEFAULT_SESSION_NAME,
                createdAt = now,
                updatedAt = now
            )
        }
        if (state.currentSessionId.isBlank() || state.sessions.none { it.id == state.currentSessionId }) {
            state.currentSessionId = state.sessions.first().id
        }
        state.sessions.forEach { session ->
            if (session.sessionTranscript.isBlank() && session.recentTasks.isNotEmpty()) {
                session.sessionTranscript = trimTranscript(session.recentTasks.joinToString("\n"))
                session.recentTasks.clear()
            }
            if (session.messages.isEmpty() && session.sessionTranscript.isNotBlank()) {
                session.messages += SessionChatMessage(
                    id = "msg-${UUID.randomUUID().toString().substring(0, 8)}",
                    role = ROLE_SYSTEM,
                    content = session.sessionTranscript.trim(),
                    timestamp = session.updatedAt
                )
            }
            if (session.messages.isNotEmpty()) {
                session.sessionTranscript = buildTranscriptFromMessages(session.messages)
            }
            if (session.errorLessons.isNotEmpty()) {
                session.condensedSummary = appendDatedBullet(
                    session.condensedSummary,
                    todayTag(),
                    session.errorLessons.joinToString("；")
                )
                session.errorLessons.clear()
            }
        }
        migrateLegacyGlobalMemory(state)
        return state
    }

    private fun normalizeStateJson(raw: String): String {
        val root = gson.fromJson(raw, JsonObject::class.java) ?: return raw
        if (!root.has("currentSessionId") || root.get("currentSessionId").isJsonNull) {
            root.addProperty("currentSessionId", "")
        }
        if (!root.has("globalMemoryEnabled") || root.get("globalMemoryEnabled").isJsonNull) {
            val legacy = root.get("memoryEnabled")?.takeIf { !it.isJsonNull }?.asBoolean ?: false
            root.addProperty("globalMemoryEnabled", legacy)
        }
        if (!root.has("globalPromptEnabled") || root.get("globalPromptEnabled").isJsonNull) {
            root.addProperty("globalPromptEnabled", false)
        }

        val sessions = if (root.has("sessions") && root.get("sessions").isJsonArray) {
            root.getAsJsonArray("sessions")
        } else {
            JsonArray().also { root.add("sessions", it) }
        }

        val globalMemory = if (root.has("globalMemory") && root.get("globalMemory").isJsonObject) {
            root.getAsJsonObject("globalMemory")
        } else {
            JsonObject().also { root.add("globalMemory", it) }
        }
        ensureStringField(globalMemory, "memoryText", "")
        ensureStringField(globalMemory, "promptText", "")
        if ((!globalMemory.has("memoryText") || globalMemory.get("memoryText").asString.isBlank()) &&
            (globalMemory.has("condensedSummary") || globalMemory.has("habitNotes") || globalMemory.has("errorLessons"))) {
            val merged = listOf(
                globalMemory.get("condensedSummary")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                globalMemory.get("habitNotes")?.takeIf { !it.isJsonNull }?.asString.orEmpty(),
                globalMemory.get("errorLessons")?.takeIf { !it.isJsonNull }?.asString.orEmpty()
            ).filter { it.isNotBlank() }.joinToString("\n\n")
            globalMemory.addProperty("memoryText", merged)
        }

        sessions.forEach { element ->
            val session = element as? JsonObject ?: return@forEach
            ensureStringField(session, "id", "")
            ensureStringField(session, "name", DEFAULT_SESSION_NAME)
            ensureArrayField(session, "messages")
            ensureStringField(session, "sessionTranscript", "")
            ensureStringField(session, "condensedSummary", "")
            if (session.has("habitNotes") && session.get("habitNotes").isJsonArray) {
                val merged = session.getAsJsonArray("habitNotes").mapNotNull { if (it.isJsonNull) null else it.asString }.joinToString("\n")
                session.addProperty("habitNotes", merged)
            }
            ensureStringField(session, "habitNotes", "")
            ensureStringField(session, "sessionPrompt", "")
            ensureArrayField(session, "recentTasks")
            ensureObjectField(session, "successfulTaskCounts")
            ensureArrayField(session, "errorLessons")
        }

        return root.toString()
    }

    private fun ensureStringField(target: JsonObject, key: String, defaultValue: String) {
        if (!target.has(key) || target.get(key).isJsonNull) {
            target.addProperty(key, defaultValue)
        }
    }

    private fun ensureArrayField(target: JsonObject, key: String) {
        if (!target.has(key) || target.get(key).isJsonNull || !target.get(key).isJsonArray) {
            target.add(key, JsonArray())
        }
    }

    private fun ensureObjectField(target: JsonObject, key: String) {
        if (!target.has(key) || target.get(key).isJsonNull || !target.get(key).isJsonObject) {
            target.add(key, JsonObject())
        }
    }

    private fun saveState(state: SessionMemoryState) {
        KVUtils.putString(KEY_SESSION_MEMORY_STATE, gson.toJson(state))
    }

    private fun trimTranscript(text: String): String {
        val sanitized = text.trim()
        return if (sanitized.length <= MAX_SESSION_TRANSCRIPT_CHARS) sanitized else sanitized.takeLast(MAX_SESSION_TRANSCRIPT_CHARS)
    }

    private fun shortenForPrompt(text: String, limit: Int): String {
        val sanitized = text.trim()
        return if (sanitized.length <= limit) sanitized else "...\n${sanitized.takeLast(limit)}"
    }

    private fun sanitizeInjectedPromptContext(text: String): String {
        return text.lineSequence()
            .map { sanitizeWaitAfterValue(it).trimEnd() }
            .filterNot { line ->
                val normalized = line.trim().lowercase(Locale.getDefault())
                normalized.contains("缩放因子") ||
                    normalized.contains("推荐等待") ||
                    normalized.contains("recommended wait")
            }
            .joinToString("\n")
            .trim()
    }

    private fun sanitizeWaitAfterValue(line: String): String {
        return line
            .replace(Regex("""(?i)(wait_after\s*=\s*)\d+(?:\.\d+)?"""), "$1")
            .replace(Regex("""(?i)(wait_after\s*:\s*)\d+(?:\.\d+)?"""), "$1")
            .replace(Regex("""(?i)(wait_after\s+)\d+(?:\.\d+)?"""), "$1")
    }

    private fun buildPromptSection(title: String, content: String, limit: Int): String? {
        val sanitized = sanitizeInjectedPromptContext(content)
        if (sanitized.isBlank()) return null
        return buildString {
            append("- ").append(title).append(":\n")
            append(shortenForPrompt(sanitized, limit))
        }.trimEnd()
    }

    private fun normalizeTask(task: String): String {
        val oneLine = sanitizeLine(task)
        return if (oneLine.length > 80) oneLine.take(80) + "..." else oneLine
    }

    private fun sanitizeLine(text: String): String {
        return text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
    }

    private fun buildTranscriptFromMessages(messages: List<SessionChatMessage>): String {
        val raw = messages.sortedBy { it.timestamp }
            .joinToString("\n") { message ->
                when (message.role) {
                    ROLE_USER -> "用户: ${sanitizeLine(message.content)}"
                    ROLE_ASSISTANT -> "AI: ${sanitizeLine(message.content)}"
                    else -> "系统: ${sanitizeLine(message.content)}"
                }
            }
        return trimTranscript(raw)
    }

    private fun deriveTranscript(session: SessionMemory, fallback: String): String {
        return if (session.messages.isNotEmpty()) buildTranscriptFromMessages(session.messages) else trimTranscript(fallback)
    }

    private fun migrateLegacyGlobalMemory(state: SessionMemoryState) {
        if (state.globalMemory.memoryText.isNotBlank()) return
        val legacy = state.sessions.mapNotNull { session ->
            listOf(session.condensedSummary, session.habitNotes)
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
                .takeIf { it.isNotBlank() }
        }
        if (legacy.isNotEmpty()) {
            state.globalMemory.memoryText = legacy.joinToString("\n\n")
        }
    }

    private fun appendDatedBullet(current: String, date: String, entry: String): String {
        val normalizedEntry = sanitizeLine(entry)
        if (normalizedEntry.isBlank()) return current.trim()
        val header = "[$date]"
        val trimmed = current.trim()
        if (trimmed.contains("- $normalizedEntry")) return trimmed
        return if (trimmed.isBlank()) {
            "$header\n- $normalizedEntry"
        } else if (trimmed.contains(header)) {
            "$trimmed\n- $normalizedEntry"
        } else {
            "$trimmed\n\n$header\n- $normalizedEntry"
        }
    }

    private fun appendDatedSection(current: String, date: String, header: String, body: String): String {
        val normalizedBody = body.trim()
        if (normalizedBody.isBlank()) return current.trim()
        val section = buildString {
            append("[$date] ").append(header).append("\n")
            append(normalizedBody)
        }
        val trimmed = current.trim()
        return if (trimmed.isBlank()) section else "$trimmed\n\n$section"
    }

    private fun todayTag(): String = dayFormatter.format(Date())
}