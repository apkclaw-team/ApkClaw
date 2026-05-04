package com.apk.claw.android.ui.settings

import android.content.Context
import com.apk.claw.android.R
import com.apk.claw.android.session.SessionMemory

object SessionMemoryUiFormatter {

    private val sectionPrefixes = listOf(
        "用户任务:",
        "AI执行记录:",
        "补充指令:",
        "任务结果:",
        "任务错误:",
        "任务已取消:"
    )

    fun buildTranscriptPreview(context: Context, transcript: String): String {
        if (transcript.isBlank()) {
            return context.getString(R.string.session_memory_preview_empty)
        }
        val blocks = splitTranscriptBlocks(transcript)
            .asReversed()
            .take(8)
        return blocks.joinToString("\n\n")
    }

    fun buildMemoryPreview(context: Context, session: SessionMemory?): String {
        if (session == null) {
            return context.getString(R.string.session_memory_memory_empty)
        }

        val sections = mutableListOf<String>()
        if (session.condensedSummary.isNotBlank()) {
            sections += context.getString(R.string.session_memory_condensed_summary) + "\n" + session.condensedSummary
        }
        if (session.habitNotes.isNotBlank()) {
            val habits = session.habitNotes.lines().map { it.trim() }.filter { it.isNotBlank() }.take(4)
            sections += context.getString(R.string.session_memory_habit_notes) + "\n" + habits.joinToString("\n") { "- $it" }
        }
        if (session.sessionPrompt.isNotBlank()) {
            sections += context.getString(R.string.session_memory_session_prompt) + "\n" + session.sessionPrompt
        }

        if (sections.isEmpty()) {
            return context.getString(R.string.session_memory_memory_empty)
        }
        return sections.joinToString("\n\n")
    }

    private fun splitTranscriptBlocks(transcript: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        transcript.lines().forEach { rawLine ->
            val line = rawLine.trimEnd()
            if (line.isBlank() && current.isEmpty()) {
                return@forEach
            }
            if (sectionPrefixes.any { line.startsWith(it) } && current.isNotEmpty()) {
                result += current.toString().trim()
                current.clear()
            }
            if (current.isNotEmpty()) {
                current.append("\n")
            }
            current.append(line)
        }
        if (current.isNotEmpty()) {
            result += current.toString().trim()
        }
        return result.filter { it.isNotBlank() }
    }
}
