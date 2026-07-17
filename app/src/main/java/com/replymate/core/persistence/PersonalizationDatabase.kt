package com.replymate.core.persistence

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "personalization_profile")
data class PersonalizationProfileEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val nameOrNickname: String = "",
    val personality: String = "",
    val interests: String = "",
    val habits: String = "",
    val backgroundStory: String = "",
    val relationshipStyle: String = "",
    val additionalContext: String = "",
    val customPrompt: String = "",
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) { companion object { const val SINGLETON_ID = 1 } }

@Entity(tableName = "global_writing_style")
data class GlobalWritingStyleEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val formality: String = "BALANCED", val detailLevel: String = "BALANCED",
    val humorLevel: String = "BALANCED", val directness: String = "BALANCED",
    val flirtiness: String = "NEUTRAL", val emojiUsage: String = "LIGHT",
    val slangUsage: String = "LIGHT", val greetingStyle: String = "", val closingStyle: String = "",
    val updatedAtEpochMs: Long = System.currentTimeMillis()
) { companion object { const val SINGLETON_ID = 1 } }

@Entity(tableName = "contact_style_rules")
data class ContactStyleRuleEntity(
    @PrimaryKey val contactId: String,
    val relationshipContext: String = "",
    val formality: String? = null, val detailLevel: String? = null, val humorLevel: String? = null,
    val directness: String? = null, val flirtiness: String? = null, val emojiUsage: String? = null,
    val slangUsage: String? = null, val greetingStyle: String? = null, val closingStyle: String? = null,
    val customInstructions: String = "", val enabled: Boolean = true,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Dao
interface PersonalizationDao {
    @Query("SELECT * FROM personalization_profile WHERE id = 1") fun observeProfile(): Flow<PersonalizationProfileEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveProfile(value: PersonalizationProfileEntity)
    @Query("SELECT * FROM global_writing_style WHERE id = 1") fun observeStyle(): Flow<GlobalWritingStyleEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveStyle(value: GlobalWritingStyleEntity)
    @Query("SELECT * FROM contact_style_rules WHERE contactId = :contactId") fun observeContactRule(contactId: String): Flow<ContactStyleRuleEntity?>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveContactRule(value: ContactStyleRuleEntity)
    @Query("DELETE FROM personalization_profile") suspend fun clearProfile()
    @Query("DELETE FROM global_writing_style") suspend fun clearStyle()
    @Query("DELETE FROM contact_style_rules") suspend fun clearContactRules()
    @Transaction suspend fun resetAll() { clearProfile(); clearStyle(); clearContactRules() }
}


@Entity(tableName = "playground_contacts")
data class PlaygroundContactEntity(
    @PrimaryKey val id: String, val name: String = "", val relationship: String = "", val nickname: String = "",
    val personality: String = "", val communicationStyle: String = "", val conversationSummary: String = "",
    val importantFacts: String = "", val preferences: String = "", val longTermMemory: String = "", val recentHistory: String = "",
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)
@Entity(tableName = "playground_generations", indices = [androidx.room.Index("contactId"), androidx.room.Index("createdAtEpochMs")])
data class PlaygroundGenerationEntity(
    @PrimaryKey val id: String, val contactId: String, val reply: String, val provider: String, val model: String,
    val durationMs: Long, val estimatedTokens: Int, val omittedHistoryTurns: Int, val omittedMemoryItems: Int,
    val createdAtEpochMs: Long = System.currentTimeMillis()
)
@Dao
interface PlaygroundDao {
    @Query("SELECT * FROM playground_contacts ORDER BY updatedAtEpochMs DESC") fun observeContacts(): Flow<List<PlaygroundContactEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveContact(value: PlaygroundContactEntity)
    @Query("DELETE FROM playground_contacts WHERE id = :id") suspend fun deleteContact(id: String)
    @Query("SELECT * FROM playground_generations WHERE contactId = :contactId ORDER BY createdAtEpochMs DESC") fun observeGenerations(contactId: String): Flow<List<PlaygroundGenerationEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveGeneration(value: PlaygroundGenerationEntity)
    @Query("DELETE FROM playground_generations WHERE id = :id") suspend fun deleteGeneration(id: String)
}

@Database(entities = [PersonalizationProfileEntity::class, GlobalWritingStyleEntity::class, ContactStyleRuleEntity::class, PlaygroundContactEntity::class, PlaygroundGenerationEntity::class, ContactEntity::class, ConversationEntity::class, ConversationMessageEntity::class, ConversationSummaryEntity::class, MemoryRecordEntity::class, RunningContextEntity::class, MemoryCandidateEntity::class, MemoryAuditEventEntity::class, SummaryVersionEntity::class, PlatformEventEntity::class, ReplyDraftEntity::class, DraftVersionEntity::class], version = 6, exportSchema = true)
abstract class ReplyMateDatabase : RoomDatabase() { abstract fun personalizationDao(): PersonalizationDao; abstract fun playgroundDao(): PlaygroundDao; abstract fun conversationDao(): ConversationDao; abstract fun memoryInspectorDao(): MemoryInspectorDao; abstract fun platformEventDao(): PlatformEventDao; abstract fun draftDao(): DraftDao }

@Entity(tableName = "contacts", indices = [androidx.room.Index(value = ["platform", "platformIdentifier"], unique = true), androidx.room.Index("displayName")])
data class ContactEntity(
    @PrimaryKey val id: String, val platform: String, val platformIdentifier: String, val displayName: String,
    val nickname: String = "", val relationship: String = "", val personality: String = "", val communicationStyle: String = "",
    val createdAtEpochMs: Long = System.currentTimeMillis(), val updatedAtEpochMs: Long = System.currentTimeMillis()
)
@Entity(tableName = "conversations", indices = [androidx.room.Index("contactId"), androidx.room.Index(value = ["platform", "platformConversationIdentifier"], unique = true)])
data class ConversationEntity(
    @PrimaryKey val id: String, val contactId: String, val platform: String, val platformConversationIdentifier: String,
    val lifecycle: String = "ACTIVE", val createdAtEpochMs: Long = System.currentTimeMillis(), val updatedAtEpochMs: Long = System.currentTimeMillis()
)
@Entity(tableName = "conversation_messages", indices = [androidx.room.Index(value = ["conversationId", "occurredAtEpochMs"]), androidx.room.Index("conversationId")])
data class ConversationMessageEntity(@PrimaryKey val id: String, val conversationId: String, val direction: String, val body: String, val occurredAtEpochMs: Long = System.currentTimeMillis())
@Entity(tableName = "conversation_summaries") data class ConversationSummaryEntity(@PrimaryKey val conversationId: String, val summary: String, val updatedAtEpochMs: Long = System.currentTimeMillis())
@Entity(tableName = "memory_records", indices = [androidx.room.Index(value = ["contactId", "category", "status"]), androidx.room.Index("content")])
data class MemoryRecordEntity(@PrimaryKey val id: String, val contactId: String, val category: String, val content: String, val priority: Int = 50, val status: String = "ACTIVE", val sourceMessageId: String? = null, val sourceConversationId: String? = null, val confidence: Float = 1f, val explanation: String = "", val createdAtEpochMs: Long = System.currentTimeMillis(), val updatedAtEpochMs: Long = System.currentTimeMillis())
@Entity(tableName = "running_contexts") data class RunningContextEntity(@PrimaryKey val conversationId: String, val context: String, val updatedAtEpochMs: Long = System.currentTimeMillis())

@Dao interface ConversationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertContact(contact: ContactEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveContact(contact: ContactEntity)
    @Query("SELECT * FROM contacts WHERE platform = :platform AND platformIdentifier = :identifier LIMIT 1") suspend fun contactByPlatformIdentifier(platform: String, identifier: String): ContactEntity?
    @Query("SELECT * FROM contacts WHERE id = :id") suspend fun contactById(id: String): ContactEntity?
    @Query("SELECT * FROM contacts WHERE displayName LIKE '%' || :query || '%' OR nickname LIKE '%' || :query || '%' ORDER BY displayName") suspend fun searchContacts(query: String): List<ContactEntity>
    @Query("SELECT * FROM contacts WHERE platform = :platform ORDER BY updatedAtEpochMs DESC") fun observeContacts(platform: String): Flow<List<ContactEntity>>
    @Query("SELECT COUNT(*) FROM contacts") suspend fun contactCount(): Int
    @Query("SELECT COUNT(*) FROM conversations") suspend fun conversationCount(): Int
    @Query("SELECT COUNT(*) FROM memory_records") suspend fun memoryCount(): Int
    @Query("SELECT COUNT(*) FROM contacts c LEFT JOIN conversations v ON c.id = v.contactId WHERE v.id IS NULL") suspend fun orphanContactCount(): Int
    @Query("SELECT COUNT(*) FROM memory_records m LEFT JOIN contacts c ON m.contactId = c.id WHERE c.id IS NULL") suspend fun orphanMemoryCount(): Int
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertConversation(value: ConversationEntity)
    @Query("SELECT * FROM conversations WHERE platform = :platform AND platformConversationIdentifier = :identifier LIMIT 1") suspend fun conversationByPlatformIdentifier(platform: String, identifier: String): ConversationEntity?
    @Query("SELECT * FROM conversations WHERE contactId = :contactId ORDER BY updatedAtEpochMs DESC") fun observeConversations(contactId: String): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun conversationById(id: String): ConversationEntity?
    @Query("SELECT * FROM conversations WHERE platformConversationIdentifier LIKE '%' || :query || '%'") suspend fun searchConversations(query: String): List<ConversationEntity>
    @Query("UPDATE conversations SET lifecycle = :lifecycle, updatedAtEpochMs = :updatedAt WHERE id = :conversationId") suspend fun setConversationLifecycle(conversationId: String, lifecycle: String, updatedAt: Long = System.currentTimeMillis())
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertMessage(value: ConversationMessageEntity)
    @Query("SELECT * FROM conversation_messages WHERE conversationId = :conversationId ORDER BY occurredAtEpochMs DESC LIMIT :limit") suspend fun newestMessages(conversationId: String, limit: Int): List<ConversationMessageEntity>
    @Query("SELECT * FROM conversation_messages WHERE conversationId = :conversationId ORDER BY occurredAtEpochMs ASC") fun observeMessages(conversationId: String): Flow<List<ConversationMessageEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSummary(value: ConversationSummaryEntity)
    @Query("SELECT * FROM conversation_summaries WHERE conversationId = :conversationId") suspend fun summary(conversationId: String): ConversationSummaryEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveMemory(value: MemoryRecordEntity)
    @Query("SELECT * FROM memory_records WHERE contactId = :contactId AND category = :category AND status = 'ACTIVE' ORDER BY priority DESC, updatedAtEpochMs DESC LIMIT :limit") suspend fun activeMemory(contactId: String, category: String, limit: Int): List<MemoryRecordEntity>
    @Query("SELECT * FROM memory_records WHERE contactId = :contactId ORDER BY updatedAtEpochMs DESC") fun observeMemory(contactId: String): Flow<List<MemoryRecordEntity>>
    @Query("SELECT * FROM memory_records WHERE content LIKE '%' || :query || '%' ORDER BY priority DESC") suspend fun searchMemory(query: String): List<MemoryRecordEntity>
    @Query("SELECT * FROM memory_records WHERE contactId = :contactId AND category = 'RELATIONSHIP_CONTEXT' AND status = 'ACTIVE' ORDER BY priority DESC LIMIT 1") suspend fun relationshipContext(contactId: String): MemoryRecordEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveRunningContext(value: RunningContextEntity)
    @Query("SELECT * FROM running_contexts WHERE conversationId = :conversationId") suspend fun runningContext(conversationId: String): RunningContextEntity?
}

@Entity(tableName = "memory_candidates", indices = [androidx.room.Index(value = ["contactId", "status"]), androidx.room.Index("conversationId")])
data class MemoryCandidateEntity(@PrimaryKey val id: String, val contactId: String, val conversationId: String, val category: String, val value: String, val supportingMessageId: String?, val explanation: String, val confidence: Float, val status: String = "PENDING_REVIEW", val createdAtEpochMs: Long = System.currentTimeMillis(), val updatedAtEpochMs: Long = System.currentTimeMillis())
@Entity(tableName = "memory_audit_events", indices = [androidx.room.Index(value = ["contactId", "occurredAtEpochMs"]), androidx.room.Index("memoryId")])
data class MemoryAuditEventEntity(@PrimaryKey val id: String, val contactId: String, val conversationId: String?, val memoryId: String?, val action: String, val detail: String, val occurredAtEpochMs: Long = System.currentTimeMillis())
@Entity(tableName = "summary_versions", indices = [androidx.room.Index("conversationId")])
data class SummaryVersionEntity(@PrimaryKey val id: String, val conversationId: String, val summary: String, val version: Int, val sourceThroughMessageId: String?, val createdAtEpochMs: Long = System.currentTimeMillis())

@Dao interface MemoryInspectorDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveCandidate(value: MemoryCandidateEntity)
    @Query("SELECT * FROM memory_candidates WHERE contactId = :contactId AND status = :status ORDER BY createdAtEpochMs DESC") fun observeCandidates(contactId: String, status: String = "PENDING_REVIEW"): Flow<List<MemoryCandidateEntity>>
    @Query("UPDATE memory_candidates SET status = :status, updatedAtEpochMs = :updatedAt WHERE id = :id") suspend fun setCandidateStatus(id: String, status: String, updatedAt: Long = System.currentTimeMillis())
    @Query("SELECT * FROM memory_audit_events WHERE contactId = :contactId ORDER BY occurredAtEpochMs DESC") fun observeAudit(contactId: String): Flow<List<MemoryAuditEventEntity>>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveAudit(value: MemoryAuditEventEntity)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveSummaryVersion(value: SummaryVersionEntity)
    @Query("SELECT * FROM summary_versions WHERE conversationId = :conversationId ORDER BY version DESC") fun observeSummaryVersions(conversationId: String): Flow<List<SummaryVersionEntity>>
    @Query("SELECT COALESCE(MAX(version), 0) FROM summary_versions WHERE conversationId = :conversationId") suspend fun latestSummaryVersion(conversationId: String): Int
    @Query("SELECT COUNT(*) FROM memory_records WHERE contactId = :contactId AND status = 'ACTIVE'") suspend fun activeCount(contactId: String): Int
    @Query("SELECT COUNT(*) FROM memory_candidates WHERE contactId = :contactId AND status = 'PENDING_REVIEW'") suspend fun pendingCount(contactId: String): Int
    @Query("SELECT COUNT(*) FROM memory_candidates WHERE status = 'PENDING_REVIEW'") suspend fun pendingCountAll(): Int
    @Query("SELECT COUNT(*) FROM memory_candidates WHERE contactId = :contactId AND status = 'REJECTED'") suspend fun rejectedCount(contactId: String): Int
    @Query("SELECT COUNT(*) FROM conversation_messages WHERE conversationId = :conversationId") suspend fun messageCount(conversationId: String): Int
    @Query("SELECT COUNT(*) FROM summary_versions WHERE conversationId = :conversationId") suspend fun summaryCount(conversationId: String): Int
}

@Entity(tableName = "platform_events", indices = [androidx.room.Index(value = ["fingerprint"], unique = true), androidx.room.Index(value = ["status", "timestampEpochMs"]), androidx.room.Index("notificationKey")])
data class PlatformEventEntity(@PrimaryKey val eventId: String, val platform: String, val platformUserIdentifier: String, val platformConversationIdentifier: String, val localContactId: String? = null, val localConversationId: String? = null, val displayName: String, val messageContent: String, val timestampEpochMs: Long, val direction: String, val packageName: String, val notificationKey: String, val notificationId: Int, val notificationTag: String?, val channelId: String?, val isGroupSummary: Boolean, val isSilent: Boolean, val fingerprint: String, val status: String = "QUEUED", val retryCount: Int = 0, val result: String? = null, val processingDurationMs: Long? = null, val createdAtEpochMs: Long = System.currentTimeMillis(), val updatedAtEpochMs: Long = System.currentTimeMillis())
@Dao interface PlatformEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun enqueue(value: PlatformEventEntity): Long
    @Query("SELECT * FROM platform_events WHERE status = 'QUEUED' OR (status = 'FAILED' AND retryCount < :maxRetries) ORDER BY timestampEpochMs ASC LIMIT :limit") suspend fun nextEvents(maxRetries: Int = 3, limit: Int = 25): List<PlatformEventEntity>
    @Query("UPDATE platform_events SET status = :status, result = :result, processingDurationMs = :duration, retryCount = :retryCount, localContactId = :contactId, localConversationId = :conversationId, updatedAtEpochMs = :updatedAt WHERE eventId = :eventId") suspend fun update(eventId: String, status: String, result: String?, duration: Long?, retryCount: Int, contactId: String?, conversationId: String?, updatedAt: Long = System.currentTimeMillis())
    @Query("SELECT * FROM platform_events ORDER BY createdAtEpochMs DESC LIMIT :limit") fun observeHistory(limit: Int = 200): Flow<List<PlatformEventEntity>>
    @Query("SELECT COUNT(*) FROM platform_events WHERE status = :status") suspend fun count(status: String): Int
    @Query("SELECT platform, COUNT(*) AS count FROM platform_events GROUP BY platform") suspend fun platformDistribution(): List<PlatformCount>
    @Query("SELECT AVG(processingDurationMs) FROM platform_events WHERE processingDurationMs IS NOT NULL") suspend fun averageProcessingDuration(): Double?
}

@Entity(tableName = "reply_drafts", indices = [androidx.room.Index(value=["status","createdAtEpochMs"]), androidx.room.Index("conversationId"), androidx.room.Index("contactId")])
data class ReplyDraftEntity(@PrimaryKey val id:String,val platform:String,val contactId:String,val conversationId:String,val originalMessage:String,val reply:String,val intent:String,val emotion:String,val strategy:String,val qualityScore:Float,val tokenEstimate:Int,val generationDurationMs:Long,val provider:String,val model:String,val status:String,val promptText:String,val correctiveRegeneration:Boolean,val error:String?=null,val createdAtEpochMs:Long=System.currentTimeMillis(),val updatedAtEpochMs:Long=System.currentTimeMillis())
@Entity(tableName = "draft_versions", indices=[androidx.room.Index(value=["draftId","createdAtEpochMs"])]) data class DraftVersionEntity(@PrimaryKey val id:String,val draftId:String,val reply:String,val action:String,val createdAtEpochMs:Long=System.currentTimeMillis())
data class PlatformCount(val platform: String, val count: Int)
@Dao interface DraftDao {
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun save(value:ReplyDraftEntity)
 @Query("SELECT * FROM reply_drafts ORDER BY createdAtEpochMs DESC") fun observeAll():Flow<List<ReplyDraftEntity>>
 @Query("SELECT * FROM reply_drafts WHERE id=:id") fun observe(id:String):Flow<ReplyDraftEntity?>
 @Query("SELECT * FROM reply_drafts WHERE id=:id") suspend fun byId(id:String):ReplyDraftEntity?
 @Query("UPDATE reply_drafts SET reply=:reply,status=:status,updatedAtEpochMs=:updatedAt WHERE id=:id") suspend fun updateReply(id:String,reply:String,status:String,updatedAt:Long=System.currentTimeMillis())
 @Insert(onConflict=OnConflictStrategy.REPLACE) suspend fun saveVersion(value:DraftVersionEntity)
 @Query("SELECT * FROM draft_versions WHERE draftId=:draftId ORDER BY createdAtEpochMs DESC") fun observeVersions(draftId:String):Flow<List<DraftVersionEntity>>
 @Query("UPDATE reply_drafts SET status=:status,updatedAtEpochMs=:updatedAt WHERE id=:id") suspend fun setStatus(id:String,status:String,updatedAt:Long=System.currentTimeMillis())
 @Query("SELECT COUNT(*) FROM reply_drafts") suspend fun count():Int
 @Query("SELECT AVG(generationDurationMs) FROM reply_drafts WHERE generationDurationMs > 0") suspend fun averageGenerationDuration():Double?
 @Query("SELECT AVG(tokenEstimate) FROM reply_drafts WHERE tokenEstimate > 0") suspend fun averageTokenEstimate():Double?
 @Query("SELECT COUNT(*) FROM reply_drafts WHERE correctiveRegeneration = 1") suspend fun regenerationCount():Int
 @Query("SELECT COUNT(*) FROM reply_drafts WHERE status = 'FAILED'") suspend fun failureCount():Int
}
