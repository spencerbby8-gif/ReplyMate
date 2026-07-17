package com.replymate.feature.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.replymate.ReplyMateApplication
import com.replymate.core.diagnostics.DiagnosticsSnapshot
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(app: ReplyMateApplication, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf<DiagnosticsSnapshot?>(null) }
    var exportPath by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { data = app.diagnosticsService.snapshot() }
    Scaffold(topBar = { TopAppBar(title = { Text("Developer Diagnostics") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val d = data
            if (d == null) CircularProgressIndicator() else {
                Text("ReplyMate ${d.appVersion} · database v${d.databaseVersion} · startup ${d.startupMs}ms", style = MaterialTheme.typography.titleMedium)
                Card { Column(Modifier.padding(12.dp)) { Text("Data: ${d.contacts} contacts · ${d.conversations} conversations · ${d.memories} memories · ${d.candidates} candidates · ${d.drafts} drafts"); Text("Integrity: orphan contacts ${d.orphanContacts} · orphan memories ${d.orphanMemories}") } }
                Card { Column(Modifier.padding(12.dp)) { Text("AI: avg ${d.averageGenerationMs ?: 0.0}ms · ${d.averageTokens ?: 0.0} tokens · regeneration ${(d.regenerationRate * 100).toInt()}% · failures ${d.failures}") } }
                Card { Column(Modifier.padding(12.dp)) { Text("Notifications: queue ${d.queue} · avg ${d.averageNotificationMs ?: 0.0}ms · platforms ${d.platforms}") } }
                Card { Column(Modifier.padding(12.dp)) { Text("Storage: ${d.storageBytes} bytes"); d.notes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) } }
                Button(onClick = { scope.launch { exportPath = app.diagnosticsService.exportSanitized().absolutePath } }) { Text("Export sanitized report") }
                exportPath?.let { Text("Exported local report: $it", style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
