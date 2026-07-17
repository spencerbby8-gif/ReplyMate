package com.replymate.core.ai

import android.util.Log

/** Metadata-only diagnostics. Prompt text, message text, and API keys must never be logged. */
interface AiDiagnostics {
    fun event(name: String, fields: Map<String, Any?> = emptyMap())
}
object NoOpAiDiagnostics : AiDiagnostics { override fun event(name: String, fields: Map<String, Any?>) = Unit }
class LogcatAiDiagnostics : AiDiagnostics {
    override fun event(name: String, fields: Map<String, Any?>) {
        val safe = fields.filterKeys { it !in setOf("apiKey", "prompt", "message", "response") }.entries.joinToString { "${it.key}=${it.value}" }
        Log.i(TAG, "$name ${safe.take(1000)}")
    }
    private companion object { const val TAG = "ReplyMateAI" }
}
