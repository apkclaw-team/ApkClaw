package com.apk.claw.android.agent

import android.os.Build
import android.util.DisplayMetrics
import android.view.WindowManager
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.agent.langchain.LangChain4jToolBridge
import com.apk.claw.android.agent.llm.LlmClient
import com.apk.claw.android.agent.llm.LlmClientFactory
import com.apk.claw.android.agent.llm.LlmResponse
import com.apk.claw.android.agent.llm.StreamingListener
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.tool.ToolRegistry
import com.apk.claw.android.tool.impl.GetScreenInfoTool
import com.apk.claw.android.tool.ToolResult
import com.apk.claw.android.utils.XLog
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dev.langchain4j.data.message.AiMessage
import dev.langchain4j.data.message.ChatMessage
import dev.langchain4j.data.message.SystemMessage
import dev.langchain4j.data.message.ToolExecutionResultMessage
import dev.langchain4j.data.message.UserMessage
import dev.langchain4j.agent.tool.ToolExecutionRequest
import java.io.File
import java.util.LinkedList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class DefaultAgentService : AgentService {

    companion object {
        private const val TAG = "AgentService"
        private val GSON = Gson()

        /** LLM API 调用失败时的最大重试次数 */
        private const val MAX_API_RETRIES = 3
        /** 死循环检测：滑动窗口大小 */
        private const val LOOP_DETECT_WINDOW = 4

        /** 是否将网络请求/响应原始数据输出到沙盒缓存文件，方便调试 */
        @JvmField
        var FILE_LOGGING_ENABLED = false
        @JvmField
        var FILE_LOGGING_CACHE_DIR: File? = null
    }

    private lateinit var config: AgentConfig
    private lateinit var llmClient: LlmClient
    private lateinit var toolSpecs: List<dev.langchain4j.agent.tool.ToolSpecification>
    private var executor: ExecutorService? = null
    private val running = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)

    override fun initialize(config: AgentConfig) {
        this.config = config
        this.llmClient = LlmClientFactory.create(config)
        this.toolSpecs = LangChain4jToolBridge.buildToolSpecifications()
        this.executor = Executors.newSingleThreadExecutor()
        XLog.i(TAG, "Agent initialized: provider=${config.provider}, model=${config.modelName}, streaming=${config.streaming}")
    }

    override fun updateConfig(config: AgentConfig) {
        if (running.get()) {
            cancel()
            XLog.w(TAG, "Task was running during config update, cancelled")
        }
        executor?.shutdownNow()
        initialize(config)
        XLog.i(TAG, "Agent config updated, new model: ${config.modelName}")
    }

    override fun executeTask(userPrompt: String, callback: AgentCallback) {
        if (running.get()) {
            callback.onError(0, IllegalStateException("Agent is already running a task"), 0)
            return
        }

        running.set(true)
        cancelled.set(false)

        executor?.submit {
            try {
                runAgentLoop(userPrompt, callback)
            } catch (e: Exception) {
                XLog.e(TAG, "Agent execution error", e)
                callback.onError(0, e, 0)
            } finally {
                running.set(false)
            }
        }
    }

    // ==================== 环境预检 ====================

    private fun preCheck(): String? {
        if (ClawAccessibilityService.getInstance() == null) {
            return ClawApplication.instance.getString(R.string.agent_accessibility_not_enabled)
        }
        return null
    }

    // ==================== 设备上下文 ====================

    private fun buildDeviceContext(): String {
        val app = ClawApplication.instance
        val sb = StringBuilder()
        sb.append("\n\n## 设备信息\n")
        sb.append("- 品牌: ").append(Build.BRAND).append("\n")
        sb.append("- 型号: ").append(Build.MODEL).append("\n")
        sb.append("- Android 版本: ").append(Build.VERSION.RELEASE)
            .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")

        try {
            val wm = app
                .getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            sb.append("- 屏幕分辨率: ").append(dm.widthPixels).append("x").append(dm.heightPixels).append("\n")
        } catch (e: Exception) {
            XLog.w(TAG, "Failed to get display metrics", e)
        }

        sb.append("- 已注册工具数: ").append(ToolRegistry.getAllTools().size).append("\n")

        val appName = try {
            val appInfo = app.packageManager.getApplicationInfo(app.packageName, 0)
            app.packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) { "CoPaw" }
        sb.append("\n## 本应用信息\n")
        sb.append("- 应用名: ").append(appName).append("\n")
        sb.append("- 包名: ").append(app.packageName).append("\n")
        sb.append("- 当用户提到'自己/本应用/这个应用'时，指的就是上述应用\n")

        // General operation rules
        sb.append("\n## 通用操作规则\n")
        sb.append("- 进入任何新页面后，必须先调用 get_screen_info 确认当前页面身份\n")
        sb.append("- 执行任何 tap/swipe 前，必须确认目标元素在当前页面确实存在\n")
        sb.append("- 如果操作后屏幕没有任何变化，说明操作无效或点错了位置，立即改用其他方法\n")
        sb.append("- 不要连续 3 次以上执行相同操作——如果 3 次都无效，完全换个思路\n\n")

        // WeChat-specific operation guide
        sb.append("\n## 微信操作注意事项\n")
        sb.append("### 微信底部导航\n")
        sb.append("- 微信底部 Tab（微信/通讯录/发现/我）的 TextView 是 clickable=false，必须 tap Tab 容器（以坐标中心点点击）\n")
        sb.append("- 自动化操作请保持仅在「微信」Tab 下的聊天功能，注意不要进入「发现」Tab，以免触发账号风控\n\n")
        sb.append("### 微信聊天界面操作\n")
        sb.append("- 聊天列表在「微信」Tab 下，包含所有最近会话，每个会话显示联系人名称、最后一条消息预览\n")
        sb.append("- 进入具体聊天：在聊天列表中找到目标联系人名称，tap 该区域即可\n")
        sb.append("- 聊天界面底部有输入框（EditText），点击后可输入文字；右侧有发送按钮\n")
        sb.append("- 页面顶部显示当前聊天对象名称——用它验证是否进入了正确的对话\n")
        sb.append("- 从聊天界面回退到列表：system_key(key=\"back\")\n")
        sb.append("- 回到微信首页：多次 back 或 system_key(key=\"home\") 后重新打开微信\n\n")

        // Long-term memory instructions
        sb.append("\n## 长期记忆使用规则\n")
        sb.append("- 每次任务成功完成后，必须调用 remember(action=\"save\", ...) 记录经验\n")
        sb.append("- 记录内容应包括：任务描述(task)、关键步骤(summary)、经验教训(learnings用|分隔)、是否成功(success)\n")
        sb.append("- 开始新任务时，若看到上面的[长期记忆]区域中有相关经验，直接参考它来加速执行\n")
        sb.append("- 如果需要查找特定经验，可以使用 remember(action=\"search\", keyword=\"...\")\n")
        sb.append("- 示例: remember(action=\"save\", task=\"打开微信\", summary=\"按Home→点击微信图标\", learnings=\"微信图标在桌面第2屏|图标位置约[540,1200]\", success=true)\n")

        return sb.toString()
    }

    // ==================== LLM 调用（带重试） ====================

    private fun chatWithRetry(messages: List<ChatMessage>, callback: AgentCallback, iteration: Int): LlmResponse {
        var lastException: Exception? = null
        for (attempt in 0 until MAX_API_RETRIES) {
            if (cancelled.get()) throw RuntimeException(ClawApplication.instance.getString(R.string.agent_task_cancelled))
            try {
                return if (config.streaming) {
                    val textBuilder = StringBuilder()
                    llmClient.chatStreaming(messages, toolSpecs, object : StreamingListener {
                        override fun onPartialText(token: String) {
                            textBuilder.append(token)
                            callback.onContent(iteration, token)
                        }
                        override fun onComplete(response: LlmResponse) {}
                        override fun onError(error: Throwable) {}
                    })
                } else {
                    llmClient.chat(messages, toolSpecs)
                }
            } catch (e: Exception) {
                lastException = e
                val msg = e.message ?: ""
                // Token 耗尽或认证失败不重试
                if (msg.contains("401") || msg.contains("403") || msg.contains("insufficient")) {
                    throw e
                }
                val delay = (Math.pow(2.0, attempt.toDouble()) * 1000).toLong()
                XLog.w(TAG, "LLM API call failed (attempt ${attempt + 1}/$MAX_API_RETRIES), retrying in ${delay}ms: $msg")
                try {
                    Thread.sleep(delay)
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
            }
        }
        throw lastException!!
    }

    // ==================== 死循环检测 ====================

    private data class RoundFingerprint(val screenHash: Int, val toolCall: String)

    private fun isStuckInLoop(history: LinkedList<RoundFingerprint>): Boolean {
        if (history.size < LOOP_DETECT_WINDOW) return false
        val first = history.first()
        return history.all { it == first }
    }

    // ==================== 上下文压缩 ====================

    /** 保护区：最近 N 轮完整保留 */
    private val KEEP_RECENT_ROUNDS = 3

    /** 大输出观察类工具 → 压缩后占位符 */
    private val OBSERVATION_PLACEHOLDERS = mapOf(
        "get_screen_info" to "[屏幕信息已省略]",
        "take_screenshot" to "[截图结果已省略]",
        "find_node_info" to "[节点查找结果已省略]",
        "get_installed_apps" to "[应用列表已省略]",
        "scroll_to_find" to "[滚动查找结果已省略]"
    )

    /**
     * 发送前压缩历史消息，节省 input token：
     * - get_screen_info：全局只保留最新一条完整结果
     * - 保护区（最近 KEEP_RECENT_ROUNDS 轮）：完整保留
     * - 保护区外：AI thinking 不动，tool result 压缩为一行摘要
     */
    private fun compressHistoryForSend(messages: MutableList<ChatMessage>) {
        // 压缩前统计总字符数
        val charsBefore = messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }
        val msgCountBefore = messages.size

        // 0. get_screen_info 特殊处理：无视分级，全局只保留最新一条完整结果
        val screenPlaceholder = OBSERVATION_PLACEHOLDERS["get_screen_info"]!!
        val lastScreenIdx = messages.indexOfLast {
            it is ToolExecutionResultMessage && it.toolName() == "get_screen_info"
        }
        for (i in messages.indices) {
            val msg = messages[i]
            if (msg is ToolExecutionResultMessage
                && msg.toolName() == "get_screen_info"
                && i != lastScreenIdx
                && msg.text() != screenPlaceholder
            ) {
                messages[i] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), screenPlaceholder)
            }
        }

        // 1. 找出所有 AiMessage 的索引，每个代表一轮
        val aiIndices = messages.indices.filter { messages[it] is AiMessage }
        if (aiIndices.size <= KEEP_RECENT_ROUNDS) return

        val totalRounds = aiIndices.size

        for (roundIdx in aiIndices.indices) {
            val roundFromEnd = totalRounds - roundIdx
            if (roundFromEnd <= KEEP_RECENT_ROUNDS) break // 保护区

            val aiIndex = aiIndices[roundIdx]

            // 收集本轮的 ToolExecutionResultMessage 索引
            var j = aiIndex + 1
            while (j < messages.size && messages[j] is ToolExecutionResultMessage) {
                compressToolResultMessage(messages, j)
                j++
            }
        }

        // 压缩后统计
        val charsAfter = messages.sumOf { msg ->
            when (msg) {
                is AiMessage -> (msg.text()?.length ?: 0) + (msg.toolExecutionRequests()?.sumOf { it.arguments()?.length ?: 0 } ?: 0)
                is ToolExecutionResultMessage -> msg.text().length
                is UserMessage -> msg.singleText().length
                is SystemMessage -> msg.text().length
                else -> 0
            }
        }
        val saved = charsBefore - charsAfter
        if (saved > 0) {
            XLog.i(TAG, "上下文压缩: ${charsBefore}→${charsAfter}字符, 节省${saved}字符(${saved * 100 / charsBefore}%), 轮数=${aiIndices.size}")
        }
    }

    /** 压缩 Tool Result：观察类工具用占位符，其他工具截取摘要 */
    private fun compressToolResultMessage(messages: MutableList<ChatMessage>, index: Int) {
        val msg = messages[index] as ToolExecutionResultMessage
        val text = msg.text()
        if (text.length <= 100) return // 已足够简短，无需压缩

        val placeholder = OBSERVATION_PLACEHOLDERS[msg.toolName()]
        if (placeholder != null) {
            messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), placeholder)
            return
        }

        // 其他工具：解析 JSON 提取摘要
        val compressed = summarizeToolResult(text)
        messages[index] = ToolExecutionResultMessage.from(msg.id(), msg.toolName(), compressed)
    }

    /** 将 ToolResult JSON 压缩为一行摘要 */
    private fun summarizeToolResult(resultJson: String): String {
        return try {
            val mapType = object : TypeToken<Map<String, Any?>>() {}.type
            val map: Map<String, Any?> = GSON.fromJson(resultJson, mapType)
            val isSuccess = map["isSuccess"] as? Boolean ?: false
            if (isSuccess) {
                val data = map["data"]?.toString() ?: "ok"
                "✓ " + if (data.length > 80) data.take(80) + "..." else data
            } else {
                val error = map["error"]?.toString() ?: "failed"
                "✗ " + if (error.length > 80) error.take(80) + "..." else error
            }
        } catch (_: Exception) {
            if (resultJson.length > 80) resultJson.take(80) + "..." else resultJson
        }
    }

    // ==================== 主执行循环 ====================

    private fun runAgentLoop(userPrompt: String, callback: AgentCallback) {
        // 环境预检
        preCheck()?.let {
            callback.onError(0, RuntimeException(it), 0)
            return
        }

        // 构建 System Prompt（原始 + 设备上下文 + 长期记忆）
        val memoryContext = com.apk.claw.android.utils.TaskMemory.recallForSystemPrompt()
        val fullSystemPrompt = config.systemPrompt + buildDeviceContext() + memoryContext

        val messages = mutableListOf<ChatMessage>()
        messages.add(SystemMessage.from(fullSystemPrompt))
        messages.add(UserMessage.from(userPrompt))

        var iterations = 0
        var totalTokens = 0
        val maxIterations = config.maxIterations
        val loopHistory = LinkedList<RoundFingerprint>()
        var lastScreenHash = 0

        while (iterations < maxIterations && !cancelled.get()) {
            iterations++
            callback.onLoopStart(iterations)

            // 发送前分级压缩历史消息，节省 token
            compressHistoryForSend(messages)

            // LLM 调用（带重试）
            val llmResponse: LlmResponse
            try {
                llmResponse = chatWithRetry(messages, callback, iterations)
            } catch (e: Exception) {
                XLog.e(TAG, "LLM API call failed after retries", e)
                callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_api_call_failed, e.message)), totalTokens)
                return
            }

            // 累加 token 用量
            llmResponse.tokenUsage?.totalTokenCount()?.let { totalTokens += it }

            // 将 AI 消息添加到历史（需要构造 AiMessage）
            val aiMessage = if (llmResponse.hasToolExecutionRequests()) {
                if (llmResponse.text.isNullOrEmpty()) {
                    AiMessage.from(llmResponse.toolExecutionRequests)
                } else {
                    AiMessage.from(llmResponse.text, llmResponse.toolExecutionRequests)
                }
            } else {
                AiMessage.from(llmResponse.text ?: "")
            }
            messages.add(aiMessage)

            // 非流式模式下推送思考内容
            if (!config.streaming && !llmResponse.text.isNullOrEmpty()) {
                callback.onContent(iterations, llmResponse.text)
            }

            // 如果没有工具调用，Agent 认为完成了
            if (!llmResponse.hasToolExecutionRequests()) {
                callback.onComplete(iterations, llmResponse.text ?: ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens)
                return
            }

            // 执行工具调用
            for (toolRequest in llmResponse.toolExecutionRequests) {
                if (cancelled.get()) {
                    callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens)
                    return
                }

                val toolName = toolRequest.name() ?: ""
                val displayName = ToolRegistry.getInstance().getDisplayName(toolName)
                val toolArgs = toolRequest.arguments() ?: "{}"
                callback.onToolCall(iterations, toolName, displayName, toolArgs)

                // 解析参数
                val mapType = object : TypeToken<Map<String, Any>>() {}.type
                var params: Map<String, Any>? = try {
                    GSON.fromJson(toolArgs, mapType)
                } catch (e: Exception) {
                    HashMap()
                }
                if (params == null) params = HashMap()

                val result = ToolRegistry.getInstance().executeTool(toolName, params)
                val paramsString = if (params.isEmpty()) "" else params.toString()
                callback.onToolResult(iterations, toolName, displayName, paramsString, result)

                // 检测到系统弹窗阻塞 → 截图通知用户并结束任务
                if (!result.isSuccess && result.error == GetScreenInfoTool.SYSTEM_DIALOG_BLOCKED) {
                    XLog.w(TAG, "System dialog blocked, notifying user and stopping task")
                    callback.onSystemDialogBlocked(iterations, totalTokens)
                    return
                }

                // finish 工具 → 任务完成
                if (toolName == "finish" && result.isSuccess) {
                    val finishData = result.data
                    callback.onComplete(iterations, finishData ?: ClawApplication.instance.getString(R.string.agent_task_completed), totalTokens)
                    return
                }

                // 记录指纹用于死循环检测
                if (toolName == "get_screen_info" && result.isSuccess && result.data != null) {
                    lastScreenHash = result.data.hashCode()
                } else if (toolName.isNotEmpty() && toolName != "get_screen_info") {
                    loopHistory.addLast(RoundFingerprint(lastScreenHash, "$toolName:$toolArgs"))
                    if (loopHistory.size > LOOP_DETECT_WINDOW) {
                        loopHistory.removeFirst()
                    }
                }

                // 添加工具结果到消息
                val resultJson = GSON.toJson(result)
                messages.add(ToolExecutionResultMessage.from(toolRequest, resultJson))
                XLog.d(TAG, "displayName:$displayName toolName:$toolName")
            }

            // 死循环检测
            if (isStuckInLoop(loopHistory)) {
                XLog.w(TAG, "Dead loop detected at iteration $iterations")
                messages.add(
                    UserMessage.from(
                        "[系统提示] 检测到你连续多轮执行了相同的操作且屏幕没有变化，你可能陷入了死循环。" +
                        "请尝试完全不同的方法：按 system_key(key=\"back\") 回退、滑动页面寻找目标、或重新打开 App。" +
                        "如果确实无法完成任务，请调用 finish 说明原因。"
                    )
                )
                loopHistory.clear()
            }
            XLog.d(TAG, "轮数:$iterations all=$totalTokens 本轮=${llmResponse.tokenUsage?.totalTokenCount()}")
        }

        if (cancelled.get()) {
            callback.onComplete(iterations, ClawApplication.instance.getString(R.string.agent_task_cancel), totalTokens)
        } else {
            callback.onError(iterations, RuntimeException(ClawApplication.instance.getString(R.string.agent_max_iterations, maxIterations)), totalTokens)
        }
    }

    override fun cancel() {
        cancelled.set(true)
    }

    override fun shutdown() {
        cancel()
        executor?.shutdownNow()
    }

    override fun isRunning(): Boolean = running.get()
}
