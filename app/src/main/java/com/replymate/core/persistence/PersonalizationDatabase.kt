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

@Database(entities = [PersonalizationProfileEntity::class, GlobalWritingStyleEntity::class, ContactStyleRuleEntity::class], version = 1, exportSchema = true)
abstract class ReplyMateDatabase : RoomDatabase() { abstract fun personalizationDao(): PersonalizationDao }
