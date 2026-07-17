package com.replymate.core.conversation

import com.replymate.core.ai.AiDiagnostics

/** Lifecycle coordinator shared by future notification adapters and the current Playground. */
class ConversationService(private val repository: ConversationRepository, private val planner: MemoryUpdatePlanner, private val diagnostics: AiDiagnostics, private val inspector: MemoryInspectorRepository) {
    suspend fun createOrOpen(platform: MessagingPlatform, platformContactIdentifier: String, displayName: String, platformConversationIdentifier: String, nickname: String = "", relationship: String = "", personality: String = "", communicationStyle: String = ""): Pair<Contact, Conversation> {
        val contact = repository.ensureContact(platform, platformContactIdentifier, displayName, nickname, relationship, personality, communicationStyle)
        return contact to repository.ensureConversation(contact, platformConversationIdentifier)
    }
    suspend fun recordTurn(contactId: String, conversationId: String, direction: MessageDirection, body: String): ConversationMessage {
        val message = repository.appendMessage(conversationId, direction, body)
        val recent = repository.loadMemory(contactId, conversationId, MemoryRetrievalPolicy(recentMessageLimit = 20)).recentMessages
        val plan = planner.plan(conversationId, recent)
        repository.saveUpdatePlan(plan)
        inspector.audit(contactId, conversationId, null, MemoryAuditAction.CONTEXT_UPDATED, "Running context updated")
        plan.summaryCandidate?.let { summary -> inspector.recordSummaryVersion(contactId, conversationId, summary, message.id) }
        diagnostics.event("conversation_turn_recorded", mapOf("direction" to direction, "conversationId" to conversationId, "bodyLength" to body.length))
        return message
    }
    suspend fun archive(conversationId: String) = repository.setLifecycle(conversationId, ConversationLifecycle.ARCHIVED)
    suspend fun restore(conversationId: String) = repository.setLifecycle(conversationId, ConversationLifecycle.ACTIVE)
    suspend fun memoryForGeneration(contactId: String, conversationId: String) = repository.loadMemory(contactId, conversationId)
    suspend fun addMemory(contactId: String, category: MemoryCategory, content: String, priority: Int = 50) = repository.saveMemory(contactId, category, content, priority)
}
