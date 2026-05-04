package com.apk.claw.android.channel

import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.TaskOrchestrator
import com.apk.claw.android.session.SessionMemoryCommandHandler
import com.apk.claw.android.service.ClawAccessibilityService
import com.apk.claw.android.utils.KVUtils

/**
 * 通道初始化与消息路由。
 * 负责读取本地配置初始化各通道，并将收到的消息分发给 [TaskOrchestrator]。
 */
class ChannelSetup(
    private val taskOrchestrator: TaskOrchestrator
) {

    fun setup() {
        ChannelManager.init(
            dingtalkAppKey = KVUtils.getDingtalkAppKey().ifEmpty { null },
            dingtalkAppSecret = KVUtils.getDingtalkAppSecret().ifEmpty { null },
            feishuAppId = KVUtils.getFeishuAppId().ifEmpty { null },
            feishuAppSecret = KVUtils.getFeishuAppSecret().ifEmpty { null },
            qqAppId = KVUtils.getQqAppId().ifEmpty { null },
            qqAppSecret = KVUtils.getQqAppSecret().ifEmpty { null },
            discordBotToken = KVUtils.getDiscordBotToken().ifEmpty { null },
            telegramBotToken = KVUtils.getTelegramBotToken().ifEmpty { null },
            wechatBotToken = KVUtils.getWechatBotToken().ifEmpty { null },
            wechatApiBaseUrl = KVUtils.getWechatApiBaseUrl().ifEmpty { null }
        )
        ChannelManager.setOnMessageReceivedListener(object : ChannelManager.OnMessageReceivedListener {
            override fun onMessageReceived(channel: Channel, message: String, messageID: String) {
                val app = ClawApplication.instance
                if (channel == Channel.FEISHU && SessionMemoryCommandHandler.handleIfCommand(message, messageID)) {
                    return
                }
                if (channel == Channel.FEISHU && taskOrchestrator.enqueueOrHandleRunningTaskMessage(channel, message, messageID)) {
                    return
                }
                if (!ClawAccessibilityService.isRunning()) {
                    ChannelManager.sendMessage(channel, app.getString(R.string.channel_msg_no_accessibility), messageID)
                    ChannelManager.flushMessages(channel)
                    return
                }
                if (!taskOrchestrator.tryAcquireTask(messageID, channel)) {
                    ChannelManager.sendMessage(channel, app.getString(R.string.channel_msg_task_in_progress), messageID)
                    ChannelManager.flushMessages(channel)
                    return
                }
                taskOrchestrator.startNewTask(channel, message, messageID)
            }
        })
    }
}
