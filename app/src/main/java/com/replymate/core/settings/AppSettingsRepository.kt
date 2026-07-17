package com.replymate.core.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore("replymate_settings")
enum class AppTheme { SYSTEM, LIGHT, DARK }
data class AppSettings(val theme: AppTheme = AppTheme.SYSTEM, val onboardingComplete: Boolean = false, val promptPreviewEnabled: Boolean = true)

class AppSettingsRepository(private val context: Context) {
    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { values ->
        AppSettings(
            theme = values[THEME]?.let { runCatching { AppTheme.valueOf(it) }.getOrNull() } ?: AppTheme.SYSTEM,
            onboardingComplete = values[ONBOARDING_COMPLETE] ?: false,
            promptPreviewEnabled = values[PROMPT_PREVIEW] ?: true
        )
    }
    suspend fun setTheme(theme: AppTheme) = context.settingsDataStore.edit { it[THEME] = theme.name }
    suspend fun setOnboardingComplete(value: Boolean) = context.settingsDataStore.edit { it[ONBOARDING_COMPLETE] = value }
    private companion object {
        val THEME: Preferences.Key<String> = stringPreferencesKey("theme")
        val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        val PROMPT_PREVIEW = booleanPreferencesKey("prompt_preview_enabled")
    }
}
