package com.replymate.core.conversation

/** Contract only: an approved future UI will serialize this locally to an encrypted user-selected archive. */
data class LocalExportDescriptor(val formatVersion: Int = 1, val includesContacts: Boolean = true, val includesConversations: Boolean = true, val includesMemory: Boolean = true, val includesPlayground: Boolean = false)
interface LocalExportService {
    /** API keys, Keystore material, and provider secrets are never exportable. */
    suspend fun export(descriptor: LocalExportDescriptor): Result<java.io.File>
}
