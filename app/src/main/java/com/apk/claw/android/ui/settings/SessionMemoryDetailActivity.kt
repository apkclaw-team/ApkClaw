package com.apk.claw.android.ui.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.session.SessionMemoryManager
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton

class SessionMemoryDetailActivity : BaseActivity() {

    companion object {
        private const val EXTRA_SESSION_ID = "extra_session_id"

        fun newIntent(context: Context, sessionId: String): Intent {
            return Intent(context, SessionMemoryDetailActivity::class.java)
                .putExtra(EXTRA_SESSION_ID, sessionId)
        }
    }

    private lateinit var etSessionName: EditText
    private lateinit var tvTranscriptPreview: TextView
    private lateinit var etSessionTranscript: EditText
    private lateinit var etCondensedSummary: EditText
    private lateinit var etHabitNotes: EditText
    private lateinit var etErrorLessons: EditText
    private lateinit var btnSave: KButton

    private var sessionId: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_session_memory_detail)

        sessionId = intent.getStringExtra(EXTRA_SESSION_ID).orEmpty()
            .ifBlank { SessionMemoryManager.getCurrentSessionId() }

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.session_memory_detail_title))
            showBackButton(true) { finish() }
        }

        etSessionName = findViewById(R.id.etSelectedSessionName)
        tvTranscriptPreview = findViewById(R.id.tvTranscriptPreview)
        etSessionTranscript = findViewById(R.id.etSessionTranscript)
        etCondensedSummary = findViewById(R.id.etCondensedSummary)
        etHabitNotes = findViewById(R.id.etHabitNotes)
        etErrorLessons = findViewById(R.id.etErrorLessons)
        btnSave = findViewById(R.id.btnSaveSessionMemory)

        etSessionTranscript.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                tvTranscriptPreview.text = SessionMemoryUiFormatter.buildTranscriptPreview(
                    this@SessionMemoryDetailActivity,
                    s?.toString().orEmpty()
                )
            }
        })

        btnSave.setOnClickListener {
            val updated = SessionMemoryManager.updateSessionContent(
                sessionId = sessionId,
                name = etSessionName.text.toString().trim(),
                sessionTranscript = etSessionTranscript.text.toString().trim(),
                condensedSummary = etCondensedSummary.text.toString().trim(),
                habitNotes = parseLines(etHabitNotes),
                errorLessons = parseLines(etErrorLessons)
            )
            if (!updated) {
                Toast.makeText(this, R.string.session_memory_choose_session, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            Toast.makeText(this, R.string.session_memory_saved, Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }

        loadSession()
    }

    private fun loadSession() {
        val session = SessionMemoryManager.getSession(sessionId)
        if (session == null) {
            Toast.makeText(this, R.string.session_memory_choose_session, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        etSessionName.setText(session.name)
        etSessionTranscript.setText(session.sessionTranscript)
        tvTranscriptPreview.text = SessionMemoryUiFormatter.buildTranscriptPreview(this, session.sessionTranscript)
        etCondensedSummary.setText(session.condensedSummary)
        etHabitNotes.setText(session.habitNotes)
        etErrorLessons.setText(session.sessionPrompt)
    }

    private fun parseLines(editText: EditText): List<String> {
        return editText.text.toString()
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }
}
