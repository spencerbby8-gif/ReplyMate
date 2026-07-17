package com.replymate.core.ai

import com.replymate.core.network.NetworkStatus

enum class AiProviderId { GEMINI, CEREBRAS }
data class AiProviderSettings(val provider: AiProviderId = AiProviderId.GEMINI, val model: String = DEFAULT_GEMINI_MODEL, val fallbackEnabled: Boolean = false)
const val DEFAULT_GEMINI_MODEL = "gemini-2.5-flash"

sealed interface ConnectionResult {
    data object Success : ConnectionResult
    data class Failure(val category: AiErrorCategory, val userMessage: String, val retryable: Boolean) : ConnectionResult
}
enum class AiErrorCategory { MISSING_KEY, OFFLINE, UNAUTHORIZED, RATE_LIMITED, TIMEOUT, SERVICE_UNAVAILABLE, BAD_RESPONSE, UNKNOWN }

/** Provider boundary deliberately excludes reply generation until its own approved slice. */
interface AiProvider {
    val id: AiProviderId
    suspend fun testConnection(apiKey: String, model: String): ConnectionResult
}

class AiProviderRegistry(private val providers: Set<AiProvider>) {
    fun provider(id: AiProviderId): AiProvider? = providers.firstOrNull { it.id == id }
}

fun NetworkStatus.asConnectionFailure(): ConnectionResult.Failure = ConnectionResult.Failure(AiErrorCategory.OFFLINE, "No internet connection. Check your connection and try again.", retryable = true)
