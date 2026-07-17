package com.replymate.feature.platform

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.replymate.ReplyMateApplication

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PlatformEventViewerScreen(app: ReplyMateApplication, onBack: () -> Unit) {
    val events by app.platformEvents.history().collectAsState(initial = emptyList()); var stats by remember { mutableStateOf<Map<String,Int>>(emptyMap()) }
    LaunchedEffect(events.size) { stats=app.platformEvents.statistics() }
    Scaffold(topBar={TopAppBar(title={Text("Platform Event Viewer")},navigationIcon={TextButton(onClick=onBack){Text("Back")}})}) { padding -> Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Text("Developer tool. Message content is intentionally not displayed.",style=MaterialTheme.typography.bodySmall); Text("Queue: ${stats.entries.joinToString { "${it.key}=${it.value}" }}",style=MaterialTheme.typography.bodySmall)
        if(events.isEmpty()) Text("No platform events recorded.") else events.forEach { event -> Card { Column(Modifier.padding(12.dp)) { Text("${event.platform} · ${event.status}"); Text("Event ${event.eventId}",style=MaterialTheme.typography.bodySmall); Text("Notification ${event.packageName} · id ${event.notificationId} · key ${event.notificationKey.takeLast(12)}",style=MaterialTheme.typography.bodySmall); Text("Contact ${event.localContactId ?: "unresolved"} · conversation ${event.localConversationId ?: "unresolved"}",style=MaterialTheme.typography.bodySmall); Text("Result: ${event.result ?: "queued"} · ${event.processingDurationMs ?: 0}ms · retry ${event.retryCount}",style=MaterialTheme.typography.bodySmall) } } }
    } }
}
