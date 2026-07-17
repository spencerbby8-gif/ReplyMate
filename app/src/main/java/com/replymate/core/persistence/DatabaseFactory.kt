package com.replymate.core.persistence

import android.content.Context
import androidx.room.Room
import com.replymate.core.security.DatabaseKeyProvider
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

object DatabaseFactory {
    fun create(context: Context): ReplyMateDatabase {
        SQLiteDatabase.loadLibs(context)
        val passphrase = DatabaseKeyProvider(context).databasePassphrase()
        return Room.databaseBuilder(context, ReplyMateDatabase::class.java, "replymate.db")
            .openHelperFactory(SupportFactory(passphrase, null, true))
            .build()
    }
}
