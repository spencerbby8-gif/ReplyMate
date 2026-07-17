package com.replymate.core.conversation

import com.replymate.core.persistence.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class ConversationRepository(private val dao: ConversationDao) {
    fun observeContacts(platform: MessagingPlatform) = dao.observeContacts(platform.name).map { it.map(ContactEntity::toModel) }
    fun observeConversations(contactId: String) = dao.observeConversations(contactId).map { it.map(ConversationEntity::toModel) }
    fun observeMessages(conversationId: String) = dao.observeMessages(conversationId).map { it.map(ConversationMessageEntity::toModel) }
    fun observeMemory(contactId: String) = dao.observeMemory(contactId).map { it.map(MemoryRecordEntity::toModel) }
    suspend fun ensureContact(platform: MessagingPlatform, platformIdentifier: String, displayName: String, nickname: String = "", relationship: String = "", personality: String = "", communicationStyle: String = ""): Contact {
        require(platformIdentifier.isNotBlank()) { "A platform identifier is required." }
        val existing = dao.contactByPlatformIdentifier(platform.name, platformIdentifier)
        val entity = ContactEntity(existing?.id ?: UUID.randomUUID().toString(), platform.name, platformIdentifier, displayName, nickname, relationship, personality, communicationStyle, existing?.createdAtEpochMs ?: System.currentTimeMillis(), System.currentTimeMillis())
        dao.saveContact(entity); return entity.toModel()
    }
    suspend fun ensureConversation(contact: Contact, conversationIdentifier: String): Conversation {
        require(conversationIdentifier.isNotBlank()) { "A platform conversation identifier is required." }
        val existing = dao.conversationByPlatformIdentifier(contact.platform.name, conversationIdentifier)
        if (existing != null) return existing.toModel()
        val value = ConversationEntity(UUID.randomUUID().toString(), contact.id, contact.platform.name, conversationIdentifier)
        dao.insertConversation(value); return value.toModel()
    }
    suspend fun setLifecycle(conversationId: String, lifecycle: ConversationLifecycle) = dao.setConversationLifecycle(conversationId, lifecycle.name)
    suspend fun appendMessage(conversationId: String, direction: MessageDirection, body: String, occurredAt: Long = System.currentTimeMillis()): ConversationMessage {
        require(body.isNotBlank()) { "Messages cannot be empty." }
        val entity = ConversationMessageEntity(UUID.randomUUID().toString(), conversationId, direction.name, body.trim(), occurredAt)
        dao.insertMessage(entity); return entity.toModel()
    }
    suspend fun saveMemory(contactId: String, category: MemoryCategory, content: String, priority: Int = 50, status: MemoryStatus = MemoryStatus.ACTIVE, sourceMessageId: String? = null, sourceConversationId: String? = null, confidence: Float = 1f, explanation: String = "User-created memory"): MemoryRecord {
        val entity = MemoryRecordEntity(UUID.randomUUID().toString(), contactId, category.name, content.trim(), priority.coerceIn(0, 100), status.name, sourceMessageId, sourceConversationId, confidence.coerceIn(0f, 1f), explanation)
        dao.saveMemory(entity); return entity.toModel()
    }
    suspend fun loadMemory(contactId: String, conversationId: String, policy: MemoryRetrievalPolicy = MemoryRetrievalPolicy()): MemoryContext {
        val contact = requireNotNull(dao.contactById(contactId)) { "Unknown contact." }.toModel()
        require(requireNotNull(dao.conversationById(conversationId)) { "Unknown conversation." }.contactId == contactId) { "Conversation does not belong to this contact." }
        val newest = dao.newestMessages(conversationId, policy.recentMessageLimit).map { it.toModel() }.reversed()
        return MemoryContext(contact, dao.summary(conversationId)?.summary, dao.relationshipContext(contactId)?.content,
            dao.activeMemory(contactId, MemoryCategory.IMPORTANT_FACT.name, policy.factLimit).map { it.toModel() },
            dao.activeMemory(contactId, MemoryCategory.PREFERENCE.name, policy.preferenceLimit).map { it.toModel() },
            dao.activeMemory(contactId, MemoryCategory.LONG_TERM_MEMORY.name, policy.longTermLimit).map { it.toModel() },
            dao.runningContext(conversationId)?.context, newest)
    }
    suspend fun saveUpdatePlan(plan: MemoryUpdatePlan) { dao.saveRunningContext(RunningContextEntity(plan.conversationId, plan.updatedRunningContext)); plan.summaryCandidate?.let { dao.saveSummary(ConversationSummaryEntity(plan.conversationId, it)) }; plan.candidates.forEach { dao.saveMemory(it.toEntity()) } }
    suspend fun editMemory(record: MemoryRecord, newValue: String): MemoryRecord { val updated = record.copy(content = newValue.trim(), updatedAtEpochMs = System.currentTimeMillis()); dao.saveMemory(updated.toEntity()); return updated }
    suspend fun editSummary(conversationId: String, value: String) = dao.saveSummary(ConversationSummaryEntity(conversationId, value.trim()))
    suspend fun editRunningContext(conversationId: String, value: String) = dao.saveRunningContext(RunningContextEntity(conversationId, value.trim()))
    suspend fun search(query: String): LocalSearchResult = LocalSearchResult(dao.searchContacts(query).map { it.toModel() }, dao.searchConversations(query).map { it.toModel() }, dao.searchMemory(query).map { it.toModel() })
}
private fun ContactEntity.toModel() = Contact(id, MessagingPlatform.valueOf(platform), platformIdentifier, displayName, nickname, relationship, personality, communicationStyle)
private fun ConversationEntity.toModel() = Conversation(id, contactId, MessagingPlatform.valueOf(platform), platformConversationIdentifier, ConversationLifecycle.valueOf(lifecycle))
private fun ConversationMessageEntity.toModel() = ConversationMessage(id, conversationId, MessageDirection.valueOf(direction), body, occurredAtEpochMs)
private fun MemoryRecordEntity.toModel() = MemoryRecord(id, contactId, MemoryCategory.valueOf(category), content, priority, MemoryStatus.valueOf(status), sourceMessageId, sourceConversationId, confidence, explanation, createdAtEpochMs, updatedAtEpochMs)
private fun MemoryRecord.toEntity() = MemoryRecordEntity(id, contactId, category.name, content, priority, status.name, sourceMessageId, sourceConversationId, confidence, explanation, createdAtEpochMs, updatedAtEpochMs)
