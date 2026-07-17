package com.replymate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.navigation.compose.*
import com.replymate.core.ai.PromptAssembler
import com.replymate.core.model.*
import com.replymate.feature.home.HomeScreen
import com.replymate.feature.onboarding.PersonalizationSetupScreen
import com.replymate.feature.onboarding.PromptPreviewScreen
import com.replymate.feature.settings.SettingsScreen
import com.replymate.ui.theme.ReplyMateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { ReplyMateApp() } }
}

@Composable private fun ReplyMateApp() {
    val nav = rememberNavController()
    val app = LocalContext.current.applicationContext as ReplyMateApplication
    val scope = rememberCoroutineScope()
    val personalization by app.personalization.personalization.collectAsState(initial = Personalization())
    ReplyMateTheme {
        NavHost(navController = nav, startDestination = "setup") {
            composable("setup") { PersonalizationSetupScreen(personalization, { updated -> scope.launch { app.personalization.save(updated) } }, onReset = { scope.launch { app.personalization.reset() } }, onPreview = { nav.navigate("preview") }, onFinish = { scope.launch { app.personalization.save(personalization); app.settings.setOnboardingComplete(true) }; nav.navigate("home") { popUpTo("setup") { inclusive = true } } }) }
            composable("preview") { PromptPreviewScreen(PromptAssembler().assemble(PromptRequest(DEFAULT_SYSTEM_PROMPT, personalization, latestIncomingMessage = "Example incoming message")), onBack = { nav.popBackStack() }) }
            composable("home") { HomeScreen(onPersonalization = { nav.navigate("setup") }, onSettings = { nav.navigate("settings") }) }
            composable("settings") { SettingsScreen(onPersonalization = { nav.navigate("setup") }, onBack = { nav.popBackStack() }) }
        }
    }
}
private const val DEFAULT_SYSTEM_PROMPT = "You are ReplyMate, a personal reply drafting assistant. Draft a natural reply in the user's voice. The user always reviews before sending. Never claim to have sent a message or invent facts."
