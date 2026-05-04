package com.apk.claw.android.ui.settings

import android.os.Bundle
import android.widget.EditText
import android.widget.Toast
import com.apk.claw.android.ClawApplication
import com.apk.claw.android.R
import com.apk.claw.android.base.BaseActivity
import com.apk.claw.android.utils.KVUtils
import com.apk.claw.android.widget.CommonToolbar
import com.apk.claw.android.widget.KButton

/**
 * LLM 配置页（自行填写 API Key、Base URL、模型名）
 */
class LlmConfigActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_llm_config)

        findViewById<CommonToolbar>(R.id.toolbar).apply {
            setTitle(getString(R.string.llm_config_title))
            showBackButton(true) { finish() }
        }

        val etApiKey = findViewById<EditText>(R.id.etApiKey)
        val etBaseUrl = findViewById<EditText>(R.id.etBaseUrl)
        val etModelName = findViewById<EditText>(R.id.etModelName)
        val etMaxIterations = findViewById<EditText>(R.id.etMaxIterations)

        etApiKey.setText(KVUtils.getLlmApiKey())
        etBaseUrl.setText(KVUtils.getLlmBaseUrl())
        etModelName.setText(KVUtils.getLlmModelName())
        etMaxIterations.setText(KVUtils.getAgentMaxIterations().toString())

        findViewById<KButton>(R.id.btnSave).setOnClickListener {
            val apiKey = etApiKey.text.toString().trim()
            val baseUrl = etBaseUrl.text.toString().trim()
            val modelName = etModelName.text.toString().trim().ifEmpty { "" }
            val maxIterations = etMaxIterations.text.toString().trim().toIntOrNull()

            if (apiKey.isEmpty()) {
                Toast.makeText(this, getString(R.string.llm_config_api_key_required), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (maxIterations == null || maxIterations <= 0) {
                Toast.makeText(this, getString(R.string.llm_config_max_iterations_invalid), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            KVUtils.setLlmApiKey(apiKey)
            KVUtils.setLlmBaseUrl(baseUrl)
            KVUtils.setLlmModelName(modelName)
            KVUtils.setAgentMaxIterations(maxIterations)

            ClawApplication.appViewModelInstance.updateAgentConfig()
            ClawApplication.appViewModelInstance.initAgent()
            ClawApplication.appViewModelInstance.afterInit()
            Toast.makeText(this, getString(R.string.llm_config_saved), Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
