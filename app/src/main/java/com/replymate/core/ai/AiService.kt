package com.replymate.core.ai

import com.replymate.core.network.NetworkMonitor
import com.replymate.core.network.NetworkStatus
import com.replymate.core.security.ApiKeyRepository

/** Coordinates provider configuration and safe connection checks; it does not generate drafts. */
class AiService(
    private val secrets: ApiKeyRepository,
    private val network: NetworkMonitor,
    private val registry: AiProviderRegistry,
    private val diagnostics: AiDiagnostics
) {
    suspend fun testGeminiConnection(model: String, candidateKey: String? = null): ConnectionResult {
        val apiKey = candidateKey?.trim()?.takeIf { it.isNotEmpty() } ?: secrets.readGeminiKey() ?: return ConnectionResult.Failure(AiErrorCategory.MISSING_KEY, "Add a Gemini API key first.", retryable = false)
        if (network.status.value == NetworkStatus.Unavailable) return network.status.value.asConnectionFailure()
        val provider = registry.provider(AiProviderId.GEMINI) ?: return ConnectionResult.Failure(AiErrorCategory.UNKNOWN, "Gemini is not available in this build.", retryable = false)
        return provider.testConnection(apiKey, model).also { diagnostics.event("ai_connection_result", mapOf("provider" to AiProviderId.GEMINI, "outcome" to it.javaClass.simpleName)) }
    }
}
