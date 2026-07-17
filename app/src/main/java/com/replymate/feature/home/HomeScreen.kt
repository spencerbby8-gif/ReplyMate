package com.replymate.feature.home

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HomeScreen(onPersonalization: () -> Unit, onSettings: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("ReplyMate") }, actions = { TextButton(onClick = onSettings) { Text("Settings") } }) }, bottomBar = { NavigationBar { listOf("Home", "Conversations", "AI Drafts").forEachIndexed { index, label -> NavigationBarItem(selected = index == 0, onClick = {}, icon = {}, label = { Text(label) }) } } }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Your private reply workspace", style = MaterialTheme.typography.headlineSmall)
            Card { Column(Modifier.padding(16.dp)) { Text("Foundation ready", style = MaterialTheme.typography.titleMedium); Text("Set up your voice before connecting any notification source.") } }
            Button(onClick = onPersonalization, modifier = Modifier.fillMaxWidth()) { Text("Set up my voice") }
            Text("Messaging capture, drafts, and sending are intentionally not active in Phase 1.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
