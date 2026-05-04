package com.apk.claw.android.ui.floating

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.widget.KButton

class FloatingTaskInputActivity : BaseActivity() {

    private val appViewModel = ClawApplication.appViewModelInstance
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!appViewModel.isTaskRunning()) {
            finish()
            return
        }

        setContentView(R.layout.activity_floating_task_input)

        val etFollowUp = findViewById<EditText>(R.id.etFloatingFollowUp)
        findViewById<KButton>(R.id.btnFloatingResume).setOnClickListener {
            handled = true
            appViewModel.resumeCurrentTask()
            finish()
        }
        findViewById<KButton>(R.id.btnFloatingStop).setOnClickListener {
            handled = true
            appViewModel.cancelCurrentTask()
            finish()
        }
        findViewById<KButton>(R.id.btnFloatingSend).setOnClickListener {
            val text = etFollowUp.text.toString().trim()
            if (text.isBlank()) {
                Toast.makeText(this, R.string.floating_task_followup_empty, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (appViewModel.submitFloatingFollowUp(text)) {
                handled = true
                finish()
            } else {
                Toast.makeText(this, R.string.floating_task_followup_failed, Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<android.view.View>(R.id.floatingInputBackdrop).setOnClickListener {
            handled = true
            appViewModel.resumeCurrentTask()
            finish()
        }
        findViewById<android.view.View>(R.id.floatingInputPanel).setOnClickListener { }
    }

    override fun onBackPressed() {
        handled = true
        appViewModel.resumeCurrentTask()
        super.onBackPressed()
    }

    override fun onDestroy() {
        if (!handled && appViewModel.isTaskRunning() && appViewModel.isTaskPaused()) {
            appViewModel.resumeCurrentTask()
        }
        super.onDestroy()
    }

    override fun isApplyStatusBarPadding(): Boolean = false
}