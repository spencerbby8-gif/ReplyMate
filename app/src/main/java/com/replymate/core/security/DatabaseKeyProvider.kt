package com.replymate.core.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom
import android.util.Base64

/** Keeps the randomly generated SQLCipher passphrase outside the database. */
class DatabaseKeyProvider(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(
        context, "replymate_secure_storage",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun databasePassphrase(): ByteArray {
        val current = preferences.getString(KEY, null)
        if (current != null) return Base64.decode(current, Base64.NO_WRAP)
        return ByteArray(32).also { bytes ->
            SecureRandom().nextBytes(bytes)
            preferences.edit().putString(KEY, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()
        }
    }
    private companion object { const val KEY = "database_passphrase_v1" }
}
