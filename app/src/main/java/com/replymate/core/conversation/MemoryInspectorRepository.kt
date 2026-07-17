package com.replymate.core.conversation

import com.replymate.core.persistence.*
import kotlinx.coroutines.flow.map
import java.util.UUID

class MemoryInspectorRepository(private val dao: MemoryInspectorDao, private val conversationRepository: ConversationRepository) {
    fun candidates(contactId: String) = dao.observeCandidates(contactId).map { rows -> rows.map { it.toModel() } }
    fun timeline(contactId: String) = dao.observeAudit(contactId).map { rows -> rows.map { it.toModel() } }
    fun summaryVersions(conversationId: String) = dao.observeSummaryVersions(conversationId).map { rows -> rows.map { SummaryVersion(it.id, it.conversationId, it.summary, it.version, it.createdAtEpochMs) } }
    suspend fun propose(contactId: String, conversationId: String, category: MemoryCategory, value: String, supportingMessageId: String?, explanation: String, confidence: Float): MemoryCandidate {
        val entity = MemoryCandidateEntity(UUID.randomUUID().toString(), contactId, conversationId, category.name, value.trim(), supportingMessageId, explanation, confidence.coerceIn(0f, 1f))
        dao.saveCandidate(entity); audit(contactId, conversationId, null, MemoryAuditAction.CANDIDATE_PROPOSED, "Candidate proposed: ${entity.value.take(120)}"); return entity.toModel()
    }
    suspend fun approve(candidate: MemoryCandidate, editedValue: String = candidate.value): MemoryRecord {
        val memory = conversationRepository.saveMemory(candidate.contactId, candidate.category, editedValue, status = MemoryStatus.ACTIVE, sourceMessageId = candidate.supportingMessageId, sourceConversationId = candidate.conversationId, confidence = candidate.confidence, explanation = candidate.explanation)
        dao.setCandidateStatus(candidate.id, MemoryStatus.ACTIVE.name); audit(candidate.contactId, candidate.conversationId, memory.id, MemoryAuditAction.APPROVED, "Approved candidate ${candidate.id}"); return memory
    }
    suspend fun reject(candidate: MemoryCandidate, ignored: Boolean = false) { dao.setCandidateStatus(candidate.id, if (ignored) MemoryStatus.EXPIRED.name else MemoryStatus.REJECTED.name); audit(candidate.contactId, candidate.conversationId, null, if (ignored) MemoryAuditAction.IGNORED else MemoryAuditAction.REJECTED, "Candidate ${candidate.id}") }
    suspend fun recordSummaryVersion(contactId: String, conversationId: String, summary: String, sourceMessageId: String?) { val version = dao.latestSummaryVersion(conversationId) + 1; dao.saveSummaryVersion(SummaryVersionEntity(UUID.randomUUID().toString(), conversationId, summary, version, sourceMessageId)); audit(contactId, conversationId, null, MemoryAuditAction.SUMMARY_UPDATED, "Summary version $version created") }
    suspend fun editMemory(record: MemoryRecord, value: String): MemoryRecord { val updated = conversationRepository.editMemory(record, value); audit(record.contactId, record.sourceConversationId, record.id, MemoryAuditAction.EDITED, "Memory edited"); return updated }
    suspend fun editSummary(contactId: String, conversationId: String, value: String) { conversationRepository.editSummary(conversationId, value); audit(contactId, conversationId, null, MemoryAuditAction.SUMMARY_UPDATED, "Summary edited") }
    suspend fun editRunningContext(contactId: String, conversationId: String, value: String) { conversationRepository.editRunningContext(conversationId, value); audit(contactId, conversationId, null, MemoryAuditAction.CONTEXT_UPDATED, "Running context edited") }
    suspend fun statistics(contactId: String, conversationId: String) = MemoryStatistics(dao.activeCount(contactId), dao.pendingCount(contactId), dao.rejectedCount(contactId), dao.messageCount(conversationId), dao.summaryCount(conversationId))
    suspend fun audit(contactId: String, conversationId: String?, memoryId: String?, action: MemoryAuditAction, detail: String) = dao.saveAudit(MemoryAuditEventEntity(UUID.randomUUID().toString(), contactId, conversationId, memoryId, action.name, detail))
}
data class SummaryVersion(val id: String, val conversationId: String, val summary: String, val version: Int, val createdAtEpochMs: Long)
private fun MemoryCandidateEntity.toModel() = MemoryCandidate(id, contactId, conversationId, MemoryCategory.valueOf(category), value, supportingMessageId, explanation, confidence, MemoryStatus.valueOf(status), createdAtEpochMs)
private fun MemoryAuditEventEntity.toModel() = MemoryAuditEvent(id, contactId, conversationId, memoryId, MemoryAuditAction.valueOf(action), detail, occurredAtEpochMs)
