package com.replymate.core.conversation

import com.replymate.core.model.ConversationTurn
import com.replymate.core.model.Speaker

enum class MessagingPlatform { PLAYGROUND, TELEGRAM, WHATSAPP }
enum class ConversationLifecycle { ACTIVE, ARCHIVED, DELETED }
enum class MessageDirection { INCOMING, OUTGOING }
enum class MemoryCategory { IMPORTANT_FACT, PREFERENCE, LONG_TERM_MEMORY, RELATIONSHIP_CONTEXT }
enum class MemoryStatus { ACTIVE, PENDING_REVIEW, REJECTED, EXPIRED }

data class Contact(
    val id: String, val platform: MessagingPlatform, val platformIdentifier: String,
    val displayName: String, val nickname: String = "", val relationship: String = "",
    val personality: String = "", val communicationStyle: String = ""
)
data class Conversation(val id: String, val contactId: String, val platform: MessagingPlatform, val platformConversationIdentifier: String, val lifecycle: ConversationLifecycle = ConversationLifecycle.ACTIVE)
data class ConversationMessage(val id: String, val conversationId: String, val direction: MessageDirection, val body: String, val occurredAtEpochMs: Long)
data class MemoryRecord(val id: String, val contactId: String, val category: MemoryCategory, val content: String, val priority: Int = 50, val status: MemoryStatus = MemoryStatus.ACTIVE, val sourceMessageId: String? = null)
data class MemoryContext(val contact: Contact, val summary: String?, val relationshipContext: String?, val importantFacts: List<MemoryRecord>, val preferences: List<MemoryRecord>, val longTermMemory: List<MemoryRecord>, val runningContext: String?, val recentMessages: List<ConversationMessage>) {
    fun asTurns(): List<ConversationTurn> = recentMessages.map { ConversationTurn(if (it.direction == MessageDirection.OUTGOING) Speaker.ME else Speaker.CONTACT, it.body) }
    fun promptMemory(): List<String> = buildList {
        relationshipContext?.takeIf { it.isNotBlank() }?.let { add("Relationship context: $it") }
        summary?.takeIf { it.isNotBlank() }?.let { add("Conversation summary: $it") }
        importantFacts.forEach { add("Important fact: ${it.content}") }; preferences.forEach { add("Preference: ${it.content}") }; longTermMemory.forEach { add("Long-term memory: ${it.content}") }
        runningContext?.takeIf { it.isNotBlank() }?.let { add("Running context: $it") }
    }
}
data class MemoryUpdatePlan(val conversationId: String, val updatedRunningContext: String, val summaryCandidate: String?, val candidates: List<MemoryRecord>)
data class LocalSearchResult(val contacts: List<Contact>, val conversations: List<Conversation>, val memories: List<MemoryRecord>)
