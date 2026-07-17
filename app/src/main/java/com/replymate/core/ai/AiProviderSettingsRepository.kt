package com.replymate.core.ai

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.aiProviderDataStore by preferencesDataStore("replymate_ai_provider_settings")

/** Provider selection is non-secret configuration; credentials stay in ApiKeyRepository. */
class AiProviderSettingsRepository(private val context: Context) {
    val settings: Flow<AiProviderSettings> = context.aiProviderDataStore.data.map { values ->
        AiProviderSettings(
            provider = values[PRIMARY_PROVIDER]?.let { runCatching { AiProviderId.valueOf(it) }.getOrNull() } ?: AiProviderId.GEMINI,
            model = values[GEMINI_MODEL]?.takeIf { it.isNotBlank() } ?: DEFAULT_GEMINI_MODEL,
            fallbackEnabled = values[FALLBACK_ENABLED] ?: false
        )
    }
    suspend fun setGeminiModel(model: String) = context.aiProviderDataStore.edit { it[GEMINI_MODEL] = model.trim() }
    private companion object {
        val PRIMARY_PROVIDER = stringPreferencesKey("primary_provider")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val FALLBACK_ENABLED = booleanPreferencesKey("fallback_enabled")
    }
}
