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
            .openHelperFactory(SupportFactory(passphrase, null, true)).addMigrations(MIGRATION_1_2).build()
    }
    private val MIGRATION_1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS playground_contacts (id TEXT NOT NULL, name TEXT NOT NULL, relationship TEXT NOT NULL, nickname TEXT NOT NULL, personality TEXT NOT NULL, communicationStyle TEXT NOT NULL, conversationSummary TEXT NOT NULL, importantFacts TEXT NOT NULL, preferences TEXT NOT NULL, longTermMemory TEXT NOT NULL, recentHistory TEXT NOT NULL, updatedAtEpochMs INTEGER NOT NULL, PRIMARY KEY(id))")
            db.execSQL("CREATE TABLE IF NOT EXISTS playground_generations (id TEXT NOT NULL, contactId TEXT NOT NULL, reply TEXT NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL, durationMs INTEGER NOT NULL, estimatedTokens INTEGER NOT NULL, omittedHistoryTurns INTEGER NOT NULL, omittedMemoryItems INTEGER NOT NULL, createdAtEpochMs INTEGER NOT NULL, PRIMARY KEY(id))")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_playground_generations_contactId ON playground_generations (contactId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_playground_generations_createdAtEpochMs ON playground_generations (createdAtEpochMs)")
        }
    }
}
