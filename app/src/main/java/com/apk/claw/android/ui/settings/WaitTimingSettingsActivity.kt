package com.apk.claw.android.ui.settings

import android.os.Bundle
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton

class WaitTimingSettingsActivity : BaseActivity() {

    private lateinit var etScalePercent: EditText
    private lateinit var etTapWaitMs: EditText
    private lateinit var etOpenAppWaitMs: EditText
    private lateinit var etInputWaitMs: EditText
    private lateinit var tvEffectiveSummary: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wait_timing_settings)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.wait_timing_title))
            showBackButton(true) { finish() }
        }

        etScalePercent = findViewById(R.id.etWaitScalePercent)
        etTapWaitMs = findViewById(R.id.etTapWaitMs)
        etOpenAppWaitMs = findViewById(R.id.etOpenAppWaitMs)
        etInputWaitMs = findViewById(R.id.etInputWaitMs)
        tvEffectiveSummary = findViewById(R.id.tvEffectiveWaitSummary)

        bindCurrentValues()

        findViewById<KButton>(R.id.btnRestoreWaitDefaults).setOnClickListener {
            KVUtils.resetWaitTimingDefaults()
            bindCurrentValues()
            applyAgentConfig()
            Toast.makeText(this, R.string.wait_timing_restored, Toast.LENGTH_SHORT).show()
        }

        findViewById<KButton>(R.id.btnSaveWaitTiming).setOnClickListener {
            val scale = etScalePercent.text.toString().trim().toIntOrNull()
            val tap = etTapWaitMs.text.toString().trim().toIntOrNull()
            val openApp = etOpenAppWaitMs.text.toString().trim().toIntOrNull()
            val input = etInputWaitMs.text.toString().trim().toIntOrNull()

            if (scale == null || scale !in 0..200) {
                Toast.makeText(this, R.string.wait_timing_invalid_scale, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (tap == null || tap < 0 || openApp == null || openApp < 0 || input == null || input < 0) {
                Toast.makeText(this, R.string.wait_timing_invalid_wait, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            KVUtils.setWaitScalePercent(scale)
            KVUtils.setTapWaitAfterMs(tap)
            KVUtils.setOpenAppWaitAfterMs(openApp)
            KVUtils.setInputWaitAfterMs(input)
            bindCurrentValues()
            applyAgentConfig()
            Toast.makeText(this, R.string.wait_timing_saved, Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun bindCurrentValues() {
        etScalePercent.setText(KVUtils.getWaitScalePercent().toString())
        etTapWaitMs.setText(KVUtils.getTapWaitAfterMs().toString())
        etOpenAppWaitMs.setText(KVUtils.getOpenAppWaitAfterMs().toString())
        etInputWaitMs.setText(KVUtils.getInputWaitAfterMs().toString())
        tvEffectiveSummary.text = getString(
            R.string.wait_timing_effective_summary,
            KVUtils.getEffectiveTapWaitAfterMs(),
            KVUtils.getEffectiveOpenAppWaitAfterMs(),
            KVUtils.getEffectiveInputWaitAfterMs()
        )
    }

    private fun applyAgentConfig() {
        ClawApplication.appViewModelInstance.updateAgentConfig()
        ClawApplication.appViewModelInstance.initAgent()
        ClawApplication.appViewModelInstance.afterInit()
    }
}