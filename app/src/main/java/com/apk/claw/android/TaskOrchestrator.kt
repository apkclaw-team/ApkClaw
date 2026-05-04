package com.apk.claw.android

import com.apk.claw.android.agent.AgentCallback
import com.apk.claw.android.agent.AgentConfig
import com.apk.claw.android.agent.AgentService
import com.apk.claw.android.agent.AgentServiceFactory
import com.apk.claw.android.channel.Channel
import com.apk.claw.android.channel.ChannelManager
import com.apk.claw.android.floating.FloatingCircleManager
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.session.SessionMemoryManager
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.XLog
import java.util.UUID

class TaskOrchestrator(
    private val agentConfigProvider: () -> AgentConfig,
    private val onTaskFinished: () -> Unit
) {

    companion object {
        private const val TAG = "TaskOrchestrator"
    }

    enum class LocalMessageResult {
        STARTED,
        QUEUED,
        CANCELLED,
        BUSY_OTHER_SESSION,
        SERVICE_UNAVAILABLE,
        EMPTY
    }

    private data class RunningTaskRecord(
        val task: String,
        val sessionId: String
    )

    private interface TaskOutput {
        val channel: Channel?
        fun sendText(content: String)
        fun flush()
        fun sendImage(imageBytes: ByteArray) {}
    }

    private class ChannelTaskOutput(
        override val channel: Channel,
        private val messageId: String
    ) : TaskOutput {
        override fun sendText(content: String) {
            ChannelManager.sendMessage(channel, content, messageId)
        }

        override fun flush() {
            ChannelManager.flushMessages(channel)
        }

        override fun sendImage(imageBytes: ByteArray) {
            ChannelManager.sendImage(channel, imageBytes, messageId)
        }
    }

    private object LocalTaskOutput : TaskOutput {
        override val channel: Channel? = null
        override fun sendText(content: String) = Unit
        override fun flush() = Unit
    }

    private lateinit var agentService: AgentService
    private val taskLock = Any()
    private val runningTaskRecordLock = Any()

    @Volatile
    var inProgressTaskMessageId: String = ""
        private set

    @Volatile
    var inProgressTaskChannel: Channel? = null
        private set

    @Volatile
    private var runningTaskRecord: RunningTaskRecord? = null

    @Volatile
    private var manualCancellationHandled = false

    fun initAgent() {
        agentService = AgentServiceFactory.create()
        try {
            agentService.initialize(agentConfigProvider())
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to initialize AgentService", e)
        }
    }

    fun updateAgentConfig(): Boolean {
        return try {
            val config = agentConfigProvider()
            if (::agentService.isInitialized) {
                agentService.updateConfig(config)
                XLog.d(TAG, "Agent config updated: model=${config.modelName}, temp=${config.temperature}")
                true
            } else {
                XLog.w(TAG, "AgentService not initialized, initializing with new config")
                agentService = AgentServiceFactory.create()
                agentService.initialize(config)
                true
            }
        } catch (e: Exception) {
            XLog.e(TAG, "Failed to update agent config", e)
            false
        }
    }

    fun tryAcquireTask(messageId: String, channel: Channel): Boolean {
        return acquireTask(messageId, channel)
    }

    private fun tryAcquireLocalTask(): Boolean {
        return acquireTask("local-${UUID.randomUUID().toString().substring(0, 8)}", null)
    }

    private fun acquireTask(messageId: String, channel: Channel?): Boolean {
        synchronized(taskLock) {
            if (inProgressTaskMessageId.isNotEmpty()) return false
            inProgressTaskMessageId = messageId
            inProgressTaskChannel = channel
            return true
        }
    }

    private fun releaseTask(): Pair<Channel?, String> {
        synchronized(taskLock) {
            val ch = inProgressTaskChannel
            val id = inProgressTaskMessageId
            inProgressTaskMessageId = ""
            inProgressTaskChannel = null
            return ch to id
        }
    }

    fun isTaskRunning(): Boolean {
        synchronized(taskLock) {
            return inProgressTaskMessageId.isNotEmpty()
        }
    }

    fun getRunningSessionId(): String? {
        synchronized(runningTaskRecordLock) {
            return runningTaskRecord?.sessionId
        }
    }

    fun isTaskPaused(): Boolean {
        return ::agentService.isInitialized && agentService.isPaused()
    }

    fun cancelCurrentTask() {
        if (!isTaskRunning()) return
        manualCancellationHandled = true

        val record = synchronized(runningTaskRecordLock) { runningTaskRecord }
        if (record != null) {
            SessionMemoryManager.appendSessionMessage(record.sessionId, SessionMemoryManager.ROLE_SYSTEM, "任务已取消：用户手动停止任务")
            SessionMemoryManager.recordCancellation(record.sessionId, record.task, "用户手动停止任务", "")
        }

        if (::agentService.isInitialized) {
            agentService.cancel()
        }
        clearRunningTaskRecord()

        val (channel, messageId) = releaseTask()
        if (channel != null && messageId.isNotEmpty()) {
            ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_task_cancelled), messageId)
        }
        FloatingCircleManager.setErrorState()
        onTaskFinished()
        XLog.d(TAG, "Current task cancelled by user")
    }

    fun pauseCurrentTask(): Boolean {
        if (!isTaskRunning() || !::agentService.isInitialized) return false
        return agentService.pause()
    }

    fun resumeCurrentTask(): Boolean {
        if (!isTaskRunning() || !::agentService.isInitialized) return false
        return agentService.resume()
    }

    fun submitFloatingFollowUp(message: String): Boolean {
        if (!isTaskRunning() || !::agentService.isInitialized || !agentService.isRunning()) return false
        val normalized = message.trim()
        val sessionId = getRunningSessionId() ?: return false
        if (normalized.isNotBlank()) {
            val accepted = agentService.enqueueUserInstruction(normalized)
            if (!accepted) return false
            SessionMemoryManager.appendSessionMessage(sessionId, SessionMemoryManager.ROLE_USER, normalized)
        }
        return agentService.resume()
    }

    fun enqueueOrHandleRunningTaskMessage(channel: Channel, message: String, messageId: String): Boolean {
        if (!isTaskRunning()) return false

        val normalized = message.trim()
        if (normalized.isEmpty()) return true

        if (isStopCommand(normalized)) {
            cancelCurrentTask()
            ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_task_cancelled_by_followup), messageId)
            ChannelManager.flushMessages(channel)
            return true
        }

        if (!::agentService.isInitialized || !agentService.isRunning()) {
            ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_task_in_progress), messageId)
            ChannelManager.flushMessages(channel)
            return true
        }

        val accepted = agentService.enqueueUserInstruction(normalized)
        if (accepted) {
            getRunningSessionId()?.let { sessionId ->
                SessionMemoryManager.appendSessionMessage(sessionId, SessionMemoryManager.ROLE_USER, normalized)
            }
        }
        val replyRes = if (accepted) R.string.channel_msg_followup_queued else R.string.channel_msg_followup_queue_failed
        ChannelManager.sendMessage(channel, ClawApplication.instance.getString(replyRes), messageId)
        ChannelManager.flushMessages(channel)
        return true
    }

    fun sendLocalSessionMessage(sessionId: String, message: String): LocalMessageResult {
        val normalized = message.trim()
        if (normalized.isEmpty()) return LocalMessageResult.EMPTY

        SessionMemoryManager.setCurrentSession(sessionId)

        if (isTaskRunning()) {
            val runningSessionId = getRunningSessionId()
            if (runningSessionId != sessionId) {
                return LocalMessageResult.BUSY_OTHER_SESSION
            }
            if (isStopCommand(normalized)) {
                cancelCurrentTask()
                return LocalMessageResult.CANCELLED
            }
            if (!::agentService.isInitialized || !agentService.isRunning()) {
                return LocalMessageResult.SERVICE_UNAVAILABLE
            }
            val accepted = agentService.enqueueUserInstruction(normalized)
            if (accepted) {
                SessionMemoryManager.appendSessionMessage(sessionId, SessionMemoryManager.ROLE_USER, normalized)
                return LocalMessageResult.QUEUED
            }
            return LocalMessageResult.SERVICE_UNAVAILABLE
        }

        if (!tryAcquireLocalTask()) {
            return LocalMessageResult.BUSY_OTHER_SESSION
        }
        startTask(sessionId, normalized, LocalTaskOutput, onServiceNotReady = {
            releaseTask()
        })
        return LocalMessageResult.STARTED
    }

    private fun isStopCommand(message: String): Boolean {
        val normalized = message.trim().lowercase()
        return normalized in setOf(
            "停止", "停止任务", "取消", "取消任务", "结束任务",
            "stop", "stop task", "cancel", "cancel task"
        )
    }

    fun startNewTask(channel: Channel, task: String, messageID: String) {
        val sessionId = SessionMemoryManager.getCurrentSessionId()
        startTask(
            sessionId = sessionId,
            task = task,
            output = ChannelTaskOutput(channel, messageID),
            onServiceNotReady = {
                releaseTask()
                ChannelManager.sendMessage(channel, ClawApplication.instance.getString(R.string.channel_msg_service_not_ready), messageID)
            }
        )
    }

    private fun startTask(
        sessionId: String,
        task: String,
        output: TaskOutput,
        onServiceNotReady: () -> Unit
    ) {
        if (!::agentService.isInitialized) {
            XLog.e(TAG, "AgentService not initialized, attempting to initialize")
            try {
                agentService = AgentServiceFactory.create()
                agentService.initialize(agentConfigProvider())
            } catch (e: Exception) {
                XLog.e(TAG, "Failed to initialize AgentService", e)
                onServiceNotReady()
                return
            }
        }

        ClawAccessibilityService.getInstance()?.pressHome()

        FloatingCircleManager.showTaskNotify(task, output.channel)
        SessionMemoryManager.setCurrentSession(sessionId)
        SessionMemoryManager.appendSessionMessage(sessionId, SessionMemoryManager.ROLE_USER, task)
        beginRunningTaskRecord(task, sessionId)
        manualCancellationHandled = false

        val effectiveTask = SessionMemoryManager.buildTaskPrompt(sessionId, task)
        var finishSummary = ""
        val roundBuffer = StringBuilder()

        fun flushRoundBuffer() {
            if (roundBuffer.isNotEmpty()) {
                val chunk = roundBuffer.toString().trim()
                output.sendText(chunk)
                SessionMemoryManager.appendSessionMessage(sessionId, SessionMemoryManager.ROLE_ASSISTANT, chunk)
                roundBuffer.clear()
            }
        }

        agentService.executeTask(effectiveTask, object : AgentCallback {
            override fun onLoopStart(round: Int) {
                flushRoundBuffer()
                FloatingCircleManager.setRunningState(round, output.channel)
            }

            override fun onContent(round: Int, content: String) {
                if (content.isNotEmpty()) {
                    roundBuffer.append(content)
                }
            }

            override fun onToolCall(round: Int, toolId: String, toolName: String, parameters: String) {
                XLog.d(TAG, "onToolCall: $toolId($toolName), $parameters")
            }

            override fun onToolResult(round: Int, toolId: String, toolName: String, parameters: String, result: ToolResult) {
                val app = ClawApplication.instance
                val status = if (result.isSuccess) app.getString(R.string.channel_msg_tool_success) else app.getString(R.string.channel_msg_tool_failure)
                var data = if (result.isSuccess) result.data else result.error
                if (data != null && data.length > 300) {
                    data = data.substring(0, 300) + "...(truncated)"
                }
                if (!result.isSuccess) {
                    XLog.e(TAG, "!!!!!!!!!!Fail: $toolName, $parameters $data")
                }
                XLog.e(TAG, "onToolResult: $toolName, $status $data")
                if (toolId == "finish" && (result.data?.isNotEmpty() ?: false)) {
                    finishSummary = result.data ?: ""
                    flushRoundBuffer()
                    output.sendText(result.data ?: "")
                } else {
                    if (roundBuffer.isNotEmpty()) roundBuffer.append("\n")
                    roundBuffer.append(app.getString(R.string.channel_msg_tool_execution, toolName + parameters, status))
                }
            }

            override fun onComplete(round: Int, finalAnswer: String, totalTokens: Int) {
                if (manualCancellationHandled) {
                    XLog.i(TAG, "Ignore onComplete after manual cancellation")
                    return
                }
                XLog.i(TAG, "onComplete: 轮数=$round, totalTokens=$totalTokens, answer=$finalAnswer")
                val completionText = finishSummary.ifBlank { finalAnswer }
                flushRoundBuffer()
                if (completionText.isNotBlank()) {
                    SessionMemoryManager.appendSessionMessage(sessionId, SessionMemoryManager.ROLE_ASSISTANT, completionText)
                }
                SessionMemoryManager.recordSuccess(sessionId, task, "", completionText)
                clearRunningTaskRecord()
                releaseTask()
                output.flush()
                FloatingCircleManager.setSuccessState()
                onTaskFinished()
            }

            override fun onError(round: Int, error: Exception, totalTokens: Int) {
                if (manualCancellationHandled) {
                    XLog.i(TAG, "Ignore onError after manual cancellation")
                    return
                }
                XLog.e(TAG, "onError: ${error.message}, totalTokens=$totalTokens", error)
                flushRoundBuffer()
                val message = error.message ?: "未知错误"
                SessionMemoryManager.appendSessionMessage(sessionId, SessionMemoryManager.ROLE_SYSTEM, "任务错误：$message")
                SessionMemoryManager.recordFailure(sessionId, task, message, "")
                clearRunningTaskRecord()
                releaseTask()
                output.sendText(ClawApplication.instance.getString(R.string.channel_msg_task_error, error.message))
                output.flush()
                FloatingCircleManager.setErrorState()
                onTaskFinished()
            }

            override fun onSystemDialogBlocked(round: Int, totalTokens: Int) {
                if (manualCancellationHandled) {
                    XLog.i(TAG, "Ignore onSystemDialogBlocked after manual cancellation")
                    return
                }
                XLog.w(TAG, "onSystemDialogBlocked: round=$round, totalTokens=$totalTokens")
                val blockedMsg = ClawApplication.instance.getString(R.string.channel_msg_system_dialog_blocked)
                flushRoundBuffer()
                SessionMemoryManager.appendSessionMessage(sessionId, SessionMemoryManager.ROLE_SYSTEM, blockedMsg)
                SessionMemoryManager.recordFailure(sessionId, task, blockedMsg, "")
                clearRunningTaskRecord()
                releaseTask()
                output.sendText(blockedMsg)
                try {
                    val service = ClawAccessibilityService.getInstance()
                    val bitmap = service?.takeScreenshot(5000)
                    if (bitmap != null) {
                        val stream = java.io.ByteArrayOutputStream()
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 80, stream)
                        bitmap.recycle()
                        output.sendImage(stream.toByteArray())
                    }
                } catch (e: Exception) {
                    XLog.e(TAG, "Failed to send screenshot for system dialog", e)
                }
                output.flush()
                FloatingCircleManager.setErrorState()
                onTaskFinished()
            }
        })
    }

    private fun beginRunningTaskRecord(task: String, sessionId: String) {
        synchronized(runningTaskRecordLock) {
            runningTaskRecord = RunningTaskRecord(task = task, sessionId = sessionId)
        }
    }

    private fun clearRunningTaskRecord() {
        synchronized(runningTaskRecordLock) {
            runningTaskRecord = null
        }
    }
}
