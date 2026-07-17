package com.replymate.core.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Minimal Gemini REST client used only for API-key validation/connection testing in this slice. */
class GeminiApiClient(private val diagnostics: AiDiagnostics) : AiProvider {
    override val id = AiProviderId.GEMINI

    override suspend fun testConnection(apiKey: String, model: String): ConnectionResult = withContext(Dispatchers.IO) {
        if (apiKey.isBlank() || apiKey.length < 10) return@withContext ConnectionResult.Failure(AiErrorCategory.MISSING_KEY, "Enter a valid Gemini API key.", retryable = false)
        diagnostics.event("connection_test_started", mapOf("provider" to id, "model" to model))
        try {
            val connection = (URL(MODELS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("x-goog-api-key", apiKey)
                setRequestProperty("Accept", "application/json")
            }
            val code = connection.responseCode
            connection.disconnect()
            val result = when {
                code in 200..299 -> ConnectionResult.Success
                code == 401 || code == 403 -> ConnectionResult.Failure(AiErrorCategory.UNAUTHORIZED, "Gemini did not accept this API key.", retryable = false)
                code == 429 -> ConnectionResult.Failure(AiErrorCategory.RATE_LIMITED, "Gemini is rate limiting requests. Try again shortly.", retryable = true)
                code >= 500 -> ConnectionResult.Failure(AiErrorCategory.SERVICE_UNAVAILABLE, "Gemini is temporarily unavailable. Try again later.", retryable = true)
                else -> ConnectionResult.Failure(AiErrorCategory.BAD_RESPONSE, "Gemini could not validate this key (HTTP $code).", retryable = code >= 400)
            }
            diagnostics.event("connection_test_finished", mapOf("provider" to id, "model" to model, "outcome" to result.javaClass.simpleName, "httpCode" to code))
            result
        } catch (error: java.net.SocketTimeoutException) {
            diagnostics.event("connection_test_failed", mapOf("provider" to id, "category" to AiErrorCategory.TIMEOUT))
            ConnectionResult.Failure(AiErrorCategory.TIMEOUT, "Gemini did not respond in time. Try again.", retryable = true)
        } catch (error: java.io.IOException) {
            diagnostics.event("connection_test_failed", mapOf("provider" to id, "category" to AiErrorCategory.SERVICE_UNAVAILABLE, "exception" to error.javaClass.simpleName))
            ConnectionResult.Failure(AiErrorCategory.SERVICE_UNAVAILABLE, "Unable to reach Gemini. Check your connection and try again.", retryable = true)
        } catch (error: Exception) {
            diagnostics.event("connection_test_failed", mapOf("provider" to id, "category" to AiErrorCategory.UNKNOWN, "exception" to error.javaClass.simpleName))
            ConnectionResult.Failure(AiErrorCategory.UNKNOWN, "Something went wrong while testing Gemini.", retryable = true)
        }
    }
    override suspend fun generateText(apiKey: String, model: String, prompt: String): GenerationResult = withContext(Dispatchers.IO) {
        val startedAt = System.nanoTime()
        diagnostics.event("playground_generation_started", mapOf("provider" to id, "model" to model, "promptChars" to prompt.length))
        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/${java.net.URLEncoder.encode(model, "UTF-8")}:generateContent"
            val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = CONNECT_TIMEOUT_MS; readTimeout = 30_000
                setRequestProperty("x-goog-api-key", apiKey); setRequestProperty("Content-Type", "application/json")
            }
            val payload = org.json.JSONObject().put("contents", org.json.JSONArray().put(org.json.JSONObject().put("role", "user").put("parts", org.json.JSONArray().put(org.json.JSONObject().put("text", prompt))))).toString()
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
            val code = connection.responseCode
            if (code !in 200..299) { connection.disconnect(); throw GeminiHttpException(code) }
            val body = connection.inputStream.bufferedReader().use { it.readText() }; connection.disconnect()
            val text = org.json.JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()
            if (text.isBlank()) throw IllegalStateException("Gemini returned no text")
            val duration = (System.nanoTime() - startedAt) / 1_000_000
            diagnostics.event("playground_generation_finished", mapOf("provider" to id, "model" to model, "durationMs" to duration))
            GenerationResult(text.trim(), id, model, duration)
        } catch (error: GeminiHttpException) {
            diagnostics.event("playground_generation_failed", mapOf("provider" to id, "httpCode" to error.code))
            throw error
        } catch (error: Exception) {
            diagnostics.event("playground_generation_failed", mapOf("provider" to id, "exception" to error.javaClass.simpleName))
            throw error
        }
    }

    private companion object {
        const val MODELS_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
    }
}

internal class GeminiHttpException(val code: Int) : java.io.IOException("Gemini HTTP $code")
