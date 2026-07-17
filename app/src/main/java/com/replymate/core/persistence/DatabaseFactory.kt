package com.replymate.core.persistence

import android.content.Context
import androidx.room.Room
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.replymate.core.security.DatabaseKeyProvider
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

object DatabaseFactory {
    fun create(context: Context): ReplyMateDatabase {
        SQLiteDatabase.loadLibs(context)
        val passphrase = DatabaseKeyProvider(context).databasePassphrase()
        return Room.databaseBuilder(context, ReplyMateDatabase::class.java, "replymate.db")
            .openHelperFactory(SupportFactory(passphrase, null, true)).addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS playground_contacts (id TEXT NOT NULL, name TEXT NOT NULL, relationship TEXT NOT NULL, nickname TEXT NOT NULL, personality TEXT NOT NULL, communicationStyle TEXT NOT NULL, conversationSummary TEXT NOT NULL, importantFacts TEXT NOT NULL, preferences TEXT NOT NULL, longTermMemory TEXT NOT NULL, recentHistory TEXT NOT NULL, updatedAtEpochMs INTEGER NOT NULL, PRIMARY KEY(id))")
            db.execSQL("CREATE TABLE IF NOT EXISTS playground_generations (id TEXT NOT NULL, contactId TEXT NOT NULL, reply TEXT NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL, durationMs INTEGER NOT NULL, estimatedTokens INTEGER NOT NULL, omittedHistoryTurns INTEGER NOT NULL, omittedMemoryItems INTEGER NOT NULL, createdAtEpochMs INTEGER NOT NULL, PRIMARY KEY(id))")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_playground_generations_contactId ON playground_generations (contactId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_playground_generations_createdAtEpochMs ON playground_generations (createdAtEpochMs)")
        }
    }
    private val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS contacts (id TEXT NOT NULL, platform TEXT NOT NULL, platformIdentifier TEXT NOT NULL, displayName TEXT NOT NULL, nickname TEXT NOT NULL, relationship TEXT NOT NULL, personality TEXT NOT NULL, communicationStyle TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, PRIMARY KEY(id))")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_contacts_platform_platformIdentifier ON contacts (platform, platformIdentifier)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_contacts_displayName ON contacts (displayName)")
            db.execSQL("CREATE TABLE IF NOT EXISTS conversations (id TEXT NOT NULL, contactId TEXT NOT NULL, platform TEXT NOT NULL, platformConversationIdentifier TEXT NOT NULL, lifecycle TEXT NOT NULL, createdAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, PRIMARY KEY(id))")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_conversations_contactId ON conversations (contactId)")
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_conversations_platform_platformConversationIdentifier ON conversations (platform, platformConversationIdentifier)")
            db.execSQL("CREATE TABLE IF NOT EXISTS conversation_messages (id TEXT NOT NULL, conversationId TEXT NOT NULL, direction TEXT NOT NULL, body TEXT NOT NULL, occurredAtEpochMs INTEGER NOT NULL, PRIMARY KEY(id))")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_messages_conversationId_occurredAtEpochMs ON conversation_messages (conversationId, occurredAtEpochMs)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_conversation_messages_conversationId ON conversation_messages (conversationId)")
            db.execSQL("CREATE TABLE IF NOT EXISTS conversation_summaries (conversationId TEXT NOT NULL, summary TEXT NOT NULL, updatedAtEpochMs INTEGER NOT NULL, PRIMARY KEY(conversationId))")
            db.execSQL("CREATE TABLE IF NOT EXISTS memory_records (id TEXT NOT NULL, contactId TEXT NOT NULL, category TEXT NOT NULL, content TEXT NOT NULL, priority INTEGER NOT NULL, status TEXT NOT NULL, sourceMessageId TEXT, createdAtEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL, PRIMARY KEY(id))")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_records_contactId_category_status ON memory_records (contactId, category, status)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_memory_records_content ON memory_records (content)")
            db.execSQL("CREATE TABLE IF NOT EXISTS running_contexts (conversationId TEXT NOT NULL, context TEXT NOT NULL, updatedAtEpochMs INTEGER NOT NULL, PRIMARY KEY(conversationId))")
        }
    }

}
