package com.replymate

import android.app.Application
import com.replymate.core.ai.*
import com.replymate.core.network.NetworkMonitor
import com.replymate.core.persistence.DatabaseFactory
import com.replymate.core.persistence.PersonalizationRepository
import com.replymate.core.persistence.ReplyMateDatabase
import com.replymate.core.security.ApiKeyRepository
import com.replymate.core.settings.AppSettingsRepository

/** Application-level composition root; later features receive interfaces rather than provider details. */
class ReplyMateApplication : Application() {
    lateinit var database: ReplyMateDatabase; private set
    lateinit var personalization: PersonalizationRepository; private set
    lateinit var settings: AppSettingsRepository; private set
    lateinit var apiKeys: ApiKeyRepository; private set
    lateinit var aiProviderSettings: AiProviderSettingsRepository; private set
    lateinit var networkMonitor: NetworkMonitor; private set
    lateinit var aiService: AiService; private set
    lateinit var promptPipeline: PromptPreparationPipeline; private set

    override fun onCreate() {
        super.onCreate()
        database = DatabaseFactory.create(this)
        personalization = PersonalizationRepository(database.personalizationDao())
        settings = AppSettingsRepository(this)
        apiKeys = ApiKeyRepository(this)
        aiProviderSettings = AiProviderSettingsRepository(this)
        networkMonitor = NetworkMonitor(this)
        val diagnostics = LogcatAiDiagnostics()
        aiService = AiService(apiKeys, networkMonitor, AiProviderRegistry(setOf(GeminiApiClient(diagnostics))), diagnostics)
        promptPipeline = PromptPreparationPipeline(PromptAssembler(), ConservativeTokenCounter(), diagnostics)
    }
}
