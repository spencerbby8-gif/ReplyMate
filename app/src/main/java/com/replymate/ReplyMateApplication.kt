package com.replymate

import android.app.Application
import com.replymate.core.persistence.DatabaseFactory
import com.replymate.core.persistence.ReplyMateDatabase
import com.replymate.core.persistence.PersonalizationRepository
import com.replymate.core.settings.AppSettingsRepository

class ReplyMateApplication : Application() {
    lateinit var database: ReplyMateDatabase
        private set
    lateinit var personalization: PersonalizationRepository
        private set
    lateinit var settings: AppSettingsRepository
        private set
    override fun onCreate() { super.onCreate(); database = DatabaseFactory.create(this); personalization = PersonalizationRepository(database.personalizationDao()); settings = AppSettingsRepository(this) }
}
