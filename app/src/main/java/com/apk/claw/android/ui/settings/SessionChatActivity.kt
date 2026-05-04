package com.apk.claw.android.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.AbsListView
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.TaskOrchestrator
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.session.SessionMemoryManager
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton

class SessionChatActivity : BaseActivity() {

    companion object {
        private const val EXTRA_SESSION_ID = "session_id"

        fun newIntent(context: Context, sessionId: String): Intent {
            return Intent(context, SessionChatActivity::class.java).putExtra(EXTRA_SESSION_ID, sessionId)
        }
    }

    private val appViewModel = ClawApplication.appViewModelInstance
    private val handler = Handler(Looper.getMainLooper())
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshUi(scrollToBottom = false)
            handler.postDelayed(this, 1000)
        }
    }

    private lateinit var listMessages: ListView
    private lateinit var tvStatus: TextView
    private lateinit var etInput: EditText
    private lateinit var btnSend: KButton
    private lateinit var btnCancelTask: KButton
    private lateinit var btnEditSummary: KButton
    private lateinit var btnEditHabits: KButton
    private lateinit var btnEditPrompt: KButton
    private lateinit var inputPanel: View
    private lateinit var adapter: SessionChatAdapter
    private lateinit var sessionId: String
    private var inputPanelBasePaddingBottom: Int = 0
    private var lastMessageCount: Int = -1
    private var initialScrollDone: Boolean = false
    private var shouldAutoScroll: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        if (sessionId.isBlank()) {
            finish()
            return
        }
        setContentView(R.layout.activity_session_chat)

        listMessages = findViewById(R.id.listMessages)
        tvStatus = findViewById(R.id.tvChatStatus)
        etInput = findViewById(R.id.etChatInput)
        btnSend = findViewById(R.id.btnSendChatMessage)
        btnCancelTask = findViewById(R.id.btnCancelChatTask)
        btnEditSummary = findViewById(R.id.btnEditSummary)
        btnEditHabits = findViewById(R.id.btnEditHabits)
        btnEditPrompt = findViewById(R.id.btnEditPrompt)
        inputPanel = findViewById(R.id.chatInputPanel)
        inputPanelBasePaddingBottom = inputPanel.paddingBottom
        adapter = SessionChatAdapter(this)
        listMessages.adapter = adapter
        listMessages.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView?, scrollState: Int) = Unit

            override fun onScroll(
                view: AbsListView?,
                firstVisibleItem: Int,
                visibleItemCount: Int,
                totalItemCount: Int
            ) {
                shouldAutoScroll = totalItemCount == 0 || isListAtBottom()
            }
        })

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(SessionMemoryManager.getSession(sessionId)?.name ?: getString(R.string.session_memory_title))
            showBackButton(true) { finish() }
        }

        btnSend.setOnClickListener { sendMessage() }
        btnCancelTask.setOnClickListener {
            appViewModel.cancelCurrentTask()
            refreshUi(scrollToBottom = true)
        }
        btnEditSummary.setOnClickListener { openEditor(SessionMemoryManager.FIELD_CONDENSED_SUMMARY) }
        btnEditHabits.setOnClickListener { openEditor(SessionMemoryManager.FIELD_HABIT_NOTES) }
        btnEditPrompt.setOnClickListener { openEditor(SessionMemoryManager.FIELD_SESSION_PROMPT) }
        setupKeyboardInsets()

        refreshUi(scrollToBottom = true)
    }

    override fun onResume() {
        super.onResume()
        SessionMemoryManager.setCurrentSession(sessionId)
        initialScrollDone = false
        shouldAutoScroll = true
        refreshUi(scrollToBottom = true)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(refreshRunnable)
    }

    private fun sendMessage() {
        val result = appViewModel.sendLocalSessionMessage(sessionId, etInput.text.toString())
        when (result) {
            TaskOrchestrator.LocalMessageResult.STARTED,
            TaskOrchestrator.LocalMessageResult.QUEUED -> {
                etInput.setText("")
                refreshUi(scrollToBottom = true)
            }
            TaskOrchestrator.LocalMessageResult.CANCELLED -> {
                etInput.setText("")
                Toast.makeText(this, R.string.session_memory_task_cancelled_local, Toast.LENGTH_SHORT).show()
                refreshUi(scrollToBottom = true)
            }
            TaskOrchestrator.LocalMessageResult.BUSY_OTHER_SESSION -> {
                Toast.makeText(this, R.string.session_memory_busy_other_session, Toast.LENGTH_SHORT).show()
            }
            TaskOrchestrator.LocalMessageResult.SERVICE_UNAVAILABLE -> {
                Toast.makeText(this, R.string.session_memory_service_unavailable, Toast.LENGTH_SHORT).show()
            }
            TaskOrchestrator.LocalMessageResult.EMPTY -> Unit
        }
    }

    private fun openEditor(field: String) {
        startActivity(SessionMemoryTextEditorActivity.newIntent(this, sessionId, field))
    }

    private fun setupKeyboardInsets() {
        ViewCompat.setOnApplyWindowInsetsListener(inputPanel) { view, insets ->
            val imeBottom = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val navBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val keyboardOverlap = (imeBottom - navBottom).coerceAtLeast(0)
            view.setPadding(
                view.paddingLeft,
                view.paddingTop,
                view.paddingRight,
                inputPanelBasePaddingBottom + keyboardOverlap
            )
            insets
        }
    }

    private fun refreshUi(scrollToBottom: Boolean) {
        val session = SessionMemoryManager.getSession(sessionId)
        if (session == null) {
            finish()
            return
        }
        val messages = SessionMemoryManager.getSessionMessages(sessionId)
        val hasNewMessages = messages.size > lastMessageCount
        adapter.submitList(messages)
        if (scrollToBottom || !initialScrollDone || (hasNewMessages && shouldAutoScroll)) {
            scrollToBottomAfterLayout(forceDoublePass = scrollToBottom || !initialScrollDone)
        }
        lastMessageCount = messages.size
        tvStatus.text = if (appViewModel.isTaskRunning() && appViewModel.getRunningSessionId() == sessionId) {
            getString(R.string.session_memory_chat_running)
        } else {
            getString(R.string.session_memory_chat_idle)
        }
        btnCancelTask.isEnabled = appViewModel.isTaskRunning() && appViewModel.getRunningSessionId() == sessionId
    }

    private fun scrollToBottomAfterLayout(forceDoublePass: Boolean) {
        listMessages.post {
            if (adapter.count > 0) {
                listMessages.setSelection(adapter.count - 1)
                if (forceDoublePass) {
                    listMessages.post {
                        if (adapter.count > 0) {
                            listMessages.setSelection(adapter.count - 1)
                        }
                        shouldAutoScroll = true
                        initialScrollDone = true
                    }
                } else {
                    shouldAutoScroll = true
                    initialScrollDone = true
                }
            } else {
                shouldAutoScroll = true
                initialScrollDone = true
            }
        }
    }

    private fun isListAtBottom(): Boolean {
        if (adapter.count == 0) return true
        val lastVisible = listMessages.lastVisiblePosition
        if (lastVisible < adapter.count - 1) return false
        val lastChild = listMessages.getChildAt(listMessages.childCount - 1) ?: return false
        return lastChild.bottom <= listMessages.height - listMessages.paddingBottom
    }
}
