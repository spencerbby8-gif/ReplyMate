package com.replymate.core.ai

import com.replymate.core.network.NetworkMonitor
import com.replymate.core.network.NetworkStatus
import com.replymate.core.security.ApiKeyRepository

sealed interface PlaygroundGenerationOutcome {
    data class Success(val result: GenerationResult) : PlaygroundGenerationOutcome
    data class Failure(val error: ConnectionResult.Failure) : PlaygroundGenerationOutcome
}

/** The only generation entry point in this slice. It is explicitly Playground-scoped. */
class PlaygroundGenerationService(
    private val secrets: ApiKeyRepository, private val network: NetworkMonitor,
    private val registry: AiProviderRegistry, private val diagnostics: AiDiagnostics
) {
    suspend fun generate(prepared: PreparedPrompt, settings: AiProviderSettings): PlaygroundGenerationOutcome {
        if (!prepared.fitsBudget) return PlaygroundGenerationOutcome.Failure(ConnectionResult.Failure(AiErrorCategory.BAD_RESPONSE, "The fixed prompt sections exceed the selected token budget. Shorten the test data.", false))
        if (network.status.value == NetworkStatus.Unavailable) return PlaygroundGenerationOutcome.Failure(network.status.value.asConnectionFailure())
        val key = secrets.readGeminiKey() ?: return PlaygroundGenerationOutcome.Failure(ConnectionResult.Failure(AiErrorCategory.MISSING_KEY, "Add a Gemini API key in Settings first.", false))
        val provider = registry.provider(settings.provider) ?: return PlaygroundGenerationOutcome.Failure(ConnectionResult.Failure(AiErrorCategory.UNKNOWN, "Selected provider is not available.", false))
        return try { PlaygroundGenerationOutcome.Success(provider.generateText(key, settings.model, prepared.text)) }
        catch (e: GeminiHttpException) { when (e.code) {
            401, 403 -> failure(AiErrorCategory.UNAUTHORIZED, "Gemini did not accept this API key.", false)
            429 -> failure(AiErrorCategory.RATE_LIMITED, "Gemini is rate limiting requests. Try again shortly.", true)
            else -> failure(AiErrorCategory.SERVICE_UNAVAILABLE, "Gemini is temporarily unavailable. Try again.", true)
        } }
        catch (e: java.net.SocketTimeoutException) { failure(AiErrorCategory.TIMEOUT, "Gemini did not respond in time.", true) }
        catch (e: java.io.IOException) { failure(AiErrorCategory.SERVICE_UNAVAILABLE, "Gemini could not be reached. Try again.", true) }
        catch (e: Exception) { failure(AiErrorCategory.UNKNOWN, "Playground generation failed.", true) }
    }
    private fun failure(category: AiErrorCategory, message: String, retryable: Boolean): PlaygroundGenerationOutcome.Failure {
        diagnostics.event("playground_generation_outcome", mapOf("category" to category, "retryable" to retryable))
        return PlaygroundGenerationOutcome.Failure(ConnectionResult.Failure(category, message, retryable))
    }
}
