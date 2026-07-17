package com.replymate.feature.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.replymate.core.notifications.NotificationAccess

@Composable fun WelcomeScreen(onContinue: () -> Unit) = OnboardingScaffold("Welcome to ReplyMate", "ReplyMate prepares private, review-first reply drafts from supported Android message notifications. It never sends a message automatically.", "Continue", onContinue)

@Composable fun PrivacyScreen(onContinue: () -> Unit) = OnboardingScaffold("Your data stays under your control", "When enabled later, ReplyMate reads message content exposed in notifications from sources you choose. A draft request sends only the selected conversation context to your configured AI provider. Local data is encrypted; no backend is required.", "I understand", onContinue)

@Composable fun NotificationPermissionScreen(onContinue: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var listenerEnabled by remember { mutableStateOf(NotificationAccess.isListenerEnabled(context)) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) listenerEnabled = NotificationAccess.isListenerEnabled(context) }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    Scaffold(bottomBar = { Surface(tonalElevation = 3.dp) { Button(onClick = onContinue, Modifier.fillMaxWidth().padding(16.dp)) { Text("Continue to voice setup") } } }) { padding ->
        Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Notification access", style = MaterialTheme.typography.headlineMedium)
            Text("Android requires notification-listener access before ReplyMate can observe supported message notifications. This access can be changed at any time in system settings.")
            Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (listenerEnabled) "Notification access is enabled" else "Notification access is not enabled", style = MaterialTheme.typography.titleMedium)
                Text(if (listenerEnabled) "ReplyMate is ready for a future supported-source connection." else "Open Android settings and enable ReplyMate under Notification access.")
                OutlinedButton(onClick = { context.startActivity(NotificationAccess.listenerSettingsIntent()) }) { Text("Open notification access settings") }
            } }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Card { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("ReplyMate alerts", style = MaterialTheme.typography.titleMedium)
                    Text("Allow notifications so ReplyMate can later notify you when a draft is ready. This is separate from notification access.")
                    OutlinedButton(onClick = { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }) { Text("Allow ReplyMate notifications") }
                } }
            }
            Text("You may continue without access. The app will show a repair option in Settings until access is enabled.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun OnboardingScaffold(title: String, body: String, action: String, onAction: () -> Unit) {
    Scaffold(bottomBar = { Surface(tonalElevation = 3.dp) { Button(onClick = onAction, Modifier.fillMaxWidth().padding(16.dp)) { Text(action) } } }) { padding ->
        Column(Modifier.padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Spacer(Modifier.height(48.dp)); Text(title, style = MaterialTheme.typography.headlineMedium); Text(body, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
