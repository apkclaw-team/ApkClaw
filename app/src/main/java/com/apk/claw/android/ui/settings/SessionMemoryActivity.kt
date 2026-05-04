package com.apk.claw.android.ui.settings

import android.os.Bundle
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.session.SessionMemoryManager
import com.apk.claw.android.widget.AlertDialog
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton

class SessionMemoryActivity : BaseActivity() {

    private lateinit var switchEnableSession: SwitchCompat
    private lateinit var switchEnableMemory: SwitchCompat
    private lateinit var switchEnableGlobalPrompt: SwitchCompat
    private lateinit var etNewSessionName: EditText
    private lateinit var btnCreateSession: KButton
    private lateinit var btnGlobalMemory: KButton
    private lateinit var btnGlobalPrompt: KButton
    private lateinit var listSessions: ListView
    private lateinit var tvEmptyState: TextView
    private lateinit var adapter: SessionListAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_memory)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.session_memory_title))
            showBackButton(true) { finish() }
        }

        switchEnableSession = findViewById(R.id.switchEnableSession)
        switchEnableMemory = findViewById(R.id.switchEnableMemory)
    switchEnableGlobalPrompt = findViewById(R.id.switchEnableGlobalPrompt)
        etNewSessionName = findViewById(R.id.etNewSessionName)
        btnCreateSession = findViewById(R.id.btnCreateSession)
    btnGlobalMemory = findViewById(R.id.btnGlobalMemory)
    btnGlobalPrompt = findViewById(R.id.btnGlobalPrompt)
        listSessions = findViewById(R.id.listSessions)
        tvEmptyState = findViewById(R.id.tvEmptySessions)

        adapter = SessionListAdapter(
            context = this,
            onOpen = { session ->
                SessionMemoryManager.setCurrentSession(session.id)
                startActivity(SessionChatActivity.newIntent(this, session.id))
            },
            onDelete = { session -> confirmDelete(session.id, session.name) }
        )
        listSessions.adapter = adapter

        switchEnableSession.isChecked = SessionMemoryManager.isSessionEnabled()
        switchEnableMemory.isChecked = SessionMemoryManager.isMemoryEnabled()
        switchEnableGlobalPrompt.isChecked = SessionMemoryManager.isGlobalPromptEnabled()
        switchEnableSession.setOnCheckedChangeListener { _, isChecked ->
            SessionMemoryManager.setSessionEnabled(isChecked)
        }
        switchEnableMemory.setOnCheckedChangeListener { _, isChecked ->
            SessionMemoryManager.setMemoryEnabled(isChecked)
        }
        switchEnableGlobalPrompt.setOnCheckedChangeListener { _, isChecked ->
            SessionMemoryManager.setGlobalPromptEnabled(isChecked)
        }

        btnGlobalMemory.setOnClickListener {
            startActivity(
                SessionMemoryTextEditorActivity.newIntent(
                    this,
                    "",
                    SessionMemoryManager.FIELD_GLOBAL_MEMORY
                )
            )
        }
        btnGlobalPrompt.setOnClickListener {
            startActivity(
                SessionMemoryTextEditorActivity.newIntent(
                    this,
                    "",
                    SessionMemoryManager.FIELD_GLOBAL_PROMPT
                )
            )
        }

        btnCreateSession.setOnClickListener {
            val created = SessionMemoryManager.createSession(etNewSessionName.text.toString().trim())
            etNewSessionName.setText("")
            Toast.makeText(this, getString(R.string.session_memory_created, created.name), Toast.LENGTH_SHORT).show()
            refreshSessions()
            startActivity(SessionChatActivity.newIntent(this, created.id))
        }

        refreshSessions()
    }

    override fun onResume() {
        super.onResume()
        refreshSessions()
    }

    private fun refreshSessions() {
        val sessions = SessionMemoryManager.listSessions()
        adapter.submitList(sessions, SessionMemoryManager.getCurrentSessionId())
        tvEmptyState.visibility = if (sessions.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun confirmDelete(sessionId: String, sessionName: String) {
        AlertDialog.showWarm(
            context = this,
            title = getString(R.string.session_memory_delete_title),
            message = getString(R.string.session_memory_delete_message, sessionName),
            actionTitle = getString(R.string.session_memory_delete_action),
            onAction = {
                SessionMemoryManager.deleteSession(sessionId)
                refreshSessions()
                Toast.makeText(this, R.string.session_memory_deleted, Toast.LENGTH_SHORT).show()
            }
        )
    }
}