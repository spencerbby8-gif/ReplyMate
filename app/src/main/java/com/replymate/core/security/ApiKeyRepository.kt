package com.replymate.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Secrets are deliberately isolated from Room, DataStore, exports, and diagnostics. */
class ApiKeyRepository(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context,
        "replymate_provider_secrets",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun readGeminiKey(): String? = preferences.getString(GEMINI_KEY, null)?.takeIf { it.isNotBlank() }
    suspend fun saveGeminiKey(key: String) = withContext(Dispatchers.IO) { preferences.edit().putString(GEMINI_KEY, key.trim()).commit() }
    suspend fun clearGeminiKey() = withContext(Dispatchers.IO) { preferences.edit().remove(GEMINI_KEY).commit() }
    fun isGeminiConfigured(): Boolean = readGeminiKey() != null
    fun maskedGeminiKey(): String? = readGeminiKey()?.let { key -> "••••••••${key.takeLast(4)}" }
    private companion object { const val GEMINI_KEY = "gemini_api_key_v1" }
}
