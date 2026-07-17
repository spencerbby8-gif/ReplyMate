package com.replymate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.*
import com.replymate.core.ai.PromptAssembler
import com.replymate.core.model.*
import com.replymate.core.settings.AppTheme
import com.replymate.feature.home.HomeScreen
import com.replymate.feature.ai.GeminiApiKeyScreen
import com.replymate.feature.ai.AiPlaygroundScreen
import com.replymate.feature.platform.PlatformEventViewerScreen
import com.replymate.feature.onboarding.*
import com.replymate.feature.settings.SettingsScreen
import com.replymate.ui.theme.ReplyMateTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() { override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { ReplyMateApp() } } }

@Composable private fun ReplyMateApp() {
    val app = LocalContext.current.applicationContext as ReplyMateApplication
    val settings by app.settings.settings.collectAsState(initial = null)
    val personalization by app.personalization.personalization.collectAsState(initial = Personalization())
    val aiProviderSettings by app.aiProviderSettings.settings.collectAsState(initial = com.replymate.core.ai.AiProviderSettings())
    val scope = rememberCoroutineScope()
    if (settings == null) { ReplyMateTheme { SplashScreen() }; return }
    val dark = when (settings!!.theme) { AppTheme.DARK -> true; AppTheme.LIGHT -> false; AppTheme.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme() }
    ReplyMateTheme(dark) {
        key(settings!!.onboardingComplete) {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = if (settings!!.onboardingComplete) "home" else "welcome") {
                composable("welcome") { WelcomeScreen { nav.navigate("privacy") } }
                composable("privacy") { PrivacyScreen { scope.launch { app.settings.setPrivacyAcknowledged(true) }; nav.navigate("notification-access") } }
                composable("notification-access") { NotificationPermissionScreen { nav.navigate("setup") } }
                composable("setup") { PersonalizationSetupScreen(personalization, { updated -> scope.launch { app.personalization.save(updated) } }, onReset = { scope.launch { app.personalization.reset() } }, onPreview = { nav.navigate("preview") }, onFinish = { scope.launch { app.personalization.save(personalization); app.settings.setOnboardingComplete(true) } }) }
                composable("preview") { PromptPreviewScreen(PromptAssembler().assemble(PromptRequest(DEFAULT_SYSTEM_PROMPT, personalization, latestIncomingMessage = "Example incoming message")), onBack = { nav.popBackStack() }) }
                composable("home") { HomeScreen(onPersonalization = { nav.navigate("setup") }, onSettings = { nav.navigate("settings") }) }
                composable("settings") { SettingsScreen(theme = settings!!.theme, geminiConfigured = app.apiKeys.isGeminiConfigured(), onThemeChanged = { scope.launch { app.settings.setTheme(it) } }, onPersonalization = { nav.navigate("setup") }, onGeminiSettings = { nav.navigate("gemini-settings") }, onPlayground = { nav.navigate("ai-playground") }, onPlatformEvents = { nav.navigate("platform-events") }, onBack = { nav.popBackStack() }) }
                composable("gemini-settings") { GeminiApiKeyScreen(app, aiProviderSettings.model, onBack = { nav.popBackStack() }) }
                composable("ai-playground") { AiPlaygroundScreen(app, personalization, aiProviderSettings.model, onEditPersonalization = { nav.navigate("setup") }, onBack = { nav.popBackStack() }) }
                composable("platform-events") { PlatformEventViewerScreen(app, onBack = { nav.popBackStack() }) }
            }
        }
    }
}
private const val DEFAULT_SYSTEM_PROMPT = "You are ReplyMate, a personal reply drafting assistant. Draft a natural reply in the user's voice. The user always reviews before sending. Never claim to have sent a message or invent facts."
