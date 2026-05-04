package com.apk.claw.android.agent

interface AgentService {
    fun initialize(config: AgentConfig)
    fun updateConfig(config: AgentConfig)
    fun executeTask(userPrompt: String, callback: AgentCallback)
    fun enqueueUserInstruction(message: String): Boolean
    fun pause(): Boolean
    fun resume(): Boolean
    fun cancel()
    fun shutdown()
    fun isRunning(): Boolean
    fun isPaused(): Boolean
}
