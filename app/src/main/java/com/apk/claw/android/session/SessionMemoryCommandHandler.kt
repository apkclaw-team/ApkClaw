package com.apk.claw.android.session

import com.apk.claw.android.channel.Channel
import com.apk.claw.android.channel.ChannelManager

object SessionMemoryCommandHandler {

    fun handleIfCommand(message: String, messageId: String): Boolean {
        val trimmed = message.trim()
        if (!trimmed.startsWith("/")) return false

        val parts = trimmed.split(Regex("\\s+"), limit = 3)
        val root = parts.firstOrNull()?.lowercase() ?: return false

        val reply = when (root) {
            "/memory", "/mem", "/记忆" -> handleMemoryCommand(parts)
            "/session", "/sess", "/会话" -> handleSessionCommand(parts)
            else -> null
        } ?: return false

        ChannelManager.sendMessage(Channel.FEISHU, reply, messageId)
        ChannelManager.flushMessages(Channel.FEISHU)
        return true
    }

    private fun handleMemoryCommand(parts: List<String>): String {
        val action = parts.getOrNull(1)?.lowercase()
        return when (action) {
            "on", "enable", "开启" -> {
                SessionMemoryManager.setMemoryEnabled(true)
                "全局记忆已开启，当前会话：${SessionMemoryManager.getCurrentSession()?.name ?: "默认会话"}"
            }
            "off", "disable", "关闭" -> {
                SessionMemoryManager.setMemoryEnabled(false)
                "全局记忆已关闭。"
            }
            "status", "状态", null -> {
                val current = SessionMemoryManager.getCurrentSession()
                val enabledText = if (SessionMemoryManager.isMemoryEnabled()) "开启" else "关闭"
                val summary = SessionMemoryManager.getMemoryText("", SessionMemoryManager.FIELD_GLOBAL_MEMORY)
                val promptEnabled = if (SessionMemoryManager.isGlobalPromptEnabled()) "开启" else "关闭"
                "全局记忆：$enabledText\n全局Prompt：$promptEnabled\n当前会话：${current?.name ?: "默认会话"} (${current?.id ?: "-"})\n全局记忆内容：${summary.ifBlank { "暂无内容。" }}"
            }
            else -> buildHelpText()
        }
    }

    private fun handleSessionCommand(parts: List<String>): String {
        val action = parts.getOrNull(1)?.lowercase()
        return when (action) {
            "on", "enable", "开启" -> {
                SessionMemoryManager.setSessionEnabled(true)
                "会话功能已开启，当前会话：${SessionMemoryManager.getCurrentSession()?.name ?: "默认会话"}"
            }
            "off", "disable", "关闭" -> {
                SessionMemoryManager.setSessionEnabled(false)
                "会话功能已关闭。"
            }
            "status", "状态" -> {
                val current = SessionMemoryManager.getCurrentSession()
                val enabledText = if (SessionMemoryManager.isSessionEnabled()) "开启" else "关闭"
                "会话状态：$enabledText\n当前会话：${current?.name ?: "默认会话"} (${current?.id ?: "-"})"
            }
            "list", "列表", null -> {
                val sessions = SessionMemoryManager.listSessions()
                val currentId = SessionMemoryManager.getCurrentSessionId()
                buildString {
                    append("可用会话：\n")
                    sessions.forEachIndexed { index, session ->
                        val currentMark = if (session.id == currentId) " [当前]" else ""
                        append(index + 1).append(". ")
                            .append(session.name)
                            .append(" (").append(session.id).append(")")
                            .append(currentMark)
                            .append("\n")
                    }
                }.trimEnd()
            }
            "new", "create", "新建" -> {
                val name = parts.getOrNull(2).orEmpty()
                val session = SessionMemoryManager.createSession(name)
                "已创建并切换到新会话：${session.name} (${session.id})"
            }
            "use", "continue", "切换", "使用", "继续" -> {
                val target = parts.getOrNull(2).orEmpty().trim()
                if (target.isBlank()) {
                    "请提供会话 ID 或会话名，例如：/session use session-default"
                } else {
                    val session = findSession(target)
                    if (session == null) {
                        "未找到会话：$target"
                    } else {
                        SessionMemoryManager.setCurrentSession(session.id)
                        "已切换到会话：${session.name} (${session.id})"
                    }
                }
            }
            "current", "当前" -> {
                val current = SessionMemoryManager.getCurrentSession()
                if (current == null) {
                    "当前没有可用会话。"
                } else {
                    "当前会话：${current.name} (${current.id})\n消息数：${SessionMemoryManager.getSessionMessages(current.id).size}"
                }
            }
            else -> buildHelpText()
        }
    }

    private fun findSession(target: String): SessionMemory? {
        val sessions = SessionMemoryManager.listSessions()
        return sessions.firstOrNull { it.id.equals(target, ignoreCase = true) }
            ?: sessions.firstOrNull { it.name.equals(target, ignoreCase = true) }
    }

    private fun buildHelpText(): String {
        return "可用命令：\n" +
            "/memory on|off|status\n" +
            "/session on|off|status|list\n" +
            "/session new 会话名\n" +
            "/session use 会话ID\n" +
            "/session current"
    }
}