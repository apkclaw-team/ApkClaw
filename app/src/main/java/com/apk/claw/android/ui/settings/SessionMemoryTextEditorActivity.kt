package com.apk.claw.android.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import android.view.View
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.session.SessionMemoryManager
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton

class SessionMemoryTextEditorActivity : BaseActivity() {

    companion object {
        private const val EXTRA_SESSION_ID = "session_id"
        private const val EXTRA_FIELD = "field"

        fun newIntent(context: Context, sessionId: String, field: String): Intent {
            return Intent(context, SessionMemoryTextEditorActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
                .putExtra(EXTRA_FIELD, field)
        }
    }

    private lateinit var sessionId: String
    private lateinit var field: String
    private lateinit var etContent: EditText
    private lateinit var btnSave: KButton
    private lateinit var btnWriteGlobal: KButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
        field = intent.getStringExtra(EXTRA_FIELD).orEmpty()
        if (field.isBlank()) {
            finish()
            return
        }
        setContentView(R.layout.activity_session_memory_editor)

        etContent = findViewById(R.id.etMemoryEditorContent)
        btnSave = findViewById(R.id.btnSaveMemoryEditor)
        btnWriteGlobal = findViewById(R.id.btnWriteGlobalMemoryEditor)
        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getTitleText(field))
            showBackButton(true) { finish() }
        }
        etContent.hint = getEditorHint(field)
        etContent.setText(SessionMemoryManager.getMemoryText(sessionId, field))
        btnSave.setOnClickListener {
            SessionMemoryManager.updateMemoryText(sessionId, field, etContent.text.toString())
            Toast.makeText(this, R.string.session_memory_editor_saved, Toast.LENGTH_SHORT).show()
            finish()
        }
        btnWriteGlobal.visibility = if (shouldShowWriteGlobal()) View.VISIBLE else View.GONE
        btnWriteGlobal.setOnClickListener {
            val content = etContent.text.toString().trim()
            if (content.isBlank()) {
                Toast.makeText(this, R.string.session_memory_write_global_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (SessionMemoryManager.writeSessionFieldToGlobalMemory(sessionId, field, content)) {
                Toast.makeText(this, R.string.session_memory_write_global_success, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.common_load_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun getTitleText(field: String): String {
        return when (field) {
            SessionMemoryManager.FIELD_CONDENSED_SUMMARY -> getString(R.string.session_memory_condensed_summary)
            SessionMemoryManager.FIELD_HABIT_NOTES -> getString(R.string.session_memory_habit_notes)
            SessionMemoryManager.FIELD_SESSION_PROMPT -> getString(R.string.session_memory_session_prompt)
            SessionMemoryManager.FIELD_GLOBAL_MEMORY -> getString(R.string.session_memory_global_memory)
            SessionMemoryManager.FIELD_GLOBAL_PROMPT -> getString(R.string.session_memory_global_prompt)
            else -> getString(R.string.session_memory_title)
        }
    }

    private fun getEditorHint(field: String): String {
        return when (field) {
            SessionMemoryManager.FIELD_CONDENSED_SUMMARY -> getString(R.string.session_memory_condensed_summary_hint)
            SessionMemoryManager.FIELD_HABIT_NOTES -> getString(R.string.session_memory_habit_notes_hint)
            SessionMemoryManager.FIELD_SESSION_PROMPT -> getString(R.string.session_memory_session_prompt_hint)
            SessionMemoryManager.FIELD_GLOBAL_MEMORY -> getString(R.string.session_memory_global_memory_hint)
            SessionMemoryManager.FIELD_GLOBAL_PROMPT -> getString(R.string.session_memory_global_prompt_hint)
            else -> getString(R.string.session_memory_editor_hint)
        }
    }

    private fun shouldShowWriteGlobal(): Boolean {
        if (sessionId.isBlank()) return false
        return field == SessionMemoryManager.FIELD_CONDENSED_SUMMARY ||
            field == SessionMemoryManager.FIELD_HABIT_NOTES
    }
}
