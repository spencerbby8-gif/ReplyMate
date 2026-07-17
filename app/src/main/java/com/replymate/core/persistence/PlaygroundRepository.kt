package com.replymate.core.persistence

import com.replymate.core.model.PlaygroundContact
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class PlaygroundRepository(private val dao: PlaygroundDao) {
    val contacts: Flow<List<PlaygroundContact>> = dao.observeContacts().map { it.map(PlaygroundContactEntity::toModel) }
    fun generations(contactId: String) = dao.observeGenerations(contactId)
    suspend fun save(contact: PlaygroundContact): PlaygroundContact {
        val result = if (contact.id.isBlank()) contact.copy(id = UUID.randomUUID().toString()) else contact
        dao.saveContact(result.toEntity()); return result
    }
    suspend fun deleteContact(id: String) = dao.deleteContact(id)
    suspend fun saveGeneration(entity: PlaygroundGenerationEntity) = dao.saveGeneration(entity)
    suspend fun deleteGeneration(id: String) = dao.deleteGeneration(id)
}
private fun PlaygroundContactEntity.toModel() = PlaygroundContact(id, name, relationship, nickname, personality, communicationStyle, conversationSummary, importantFacts, preferences, longTermMemory, recentHistory)
private fun PlaygroundContact.toEntity() = PlaygroundContactEntity(id, name, relationship, nickname, personality, communicationStyle, conversationSummary, importantFacts, preferences, longTermMemory, recentHistory)
