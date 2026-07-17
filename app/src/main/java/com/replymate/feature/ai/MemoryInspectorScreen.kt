package com.replymate.feature.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.replymate.ReplyMateApplication
import com.replymate.core.conversation.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun MemoryInspectorScreen(app: ReplyMateApplication, contactId: String, conversationId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope(); val memories by app.conversations.observeMemory(contactId).collectAsState(initial = emptyList()); val messages by app.conversations.observeMessages(conversationId).collectAsState(initial = emptyList()); val candidates by app.memoryInspector.candidates(contactId).collectAsState(initial = emptyList()); val audit by app.memoryInspector.timeline(contactId).collectAsState(initial = emptyList())
    var stats by remember { mutableStateOf<MemoryStatistics?>(null) }; var context by remember { mutableStateOf<MemoryContext?>(null) }
    LaunchedEffect(contactId, conversationId, memories.size, candidates.size, messages.size) { stats = app.memoryInspector.statistics(contactId, conversationId); context = app.conversationService.memoryForGeneration(contactId, conversationId) }
    Scaffold(topBar = { TopAppBar(title = { Text("Memory Inspector") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding -> Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Contact: $contactId", style = MaterialTheme.typography.bodySmall); stats?.let { InspectorSection("Memory statistics") { Text("Active ${it.active} · Pending ${it.pending} · Rejected ${it.rejected} · Messages ${it.messages} · Summary versions ${it.summaries}") } }
        context?.let { ContextEditor(it, onSummary = { value -> scope.launch { app.memoryInspector.editSummary(contactId, conversationId, value) } }, onRunning = { value -> scope.launch { app.memoryInspector.editRunningContext(contactId, conversationId, value) } }) }
        InspectorSection("Memory approval queue") { if (candidates.isEmpty()) Text("No pending candidates.") else candidates.forEach { candidate -> CandidateCard(candidate, onApprove = { value -> scope.launch { app.memoryInspector.approve(candidate, value) } }, onReject = { scope.launch { app.memoryInspector.reject(candidate) } }, onIgnore = { scope.launch { app.memoryInspector.reject(candidate, true) } }) } }
        InspectorSection("Permanent memories") { if (memories.isEmpty()) Text("No approved memories.") else memories.forEach { MemoryCard(it, onEdit = { value -> scope.launch { app.memoryInspector.editMemory(it, value) } }) } }
        InspectorSection("Recent messages") { messages.forEach { Text("${it.direction}: ${it.body}", style = MaterialTheme.typography.bodySmall) } }
        InspectorSection("Memory timeline") { audit.forEach { Text("${it.action} · ${it.detail} · ${it.occurredAtEpochMs}", style = MaterialTheme.typography.bodySmall) } }
    } }
}

@Composable private fun ContextEditor(context: MemoryContext, onSummary: (String) -> Unit, onRunning: (String) -> Unit) = InspectorSection("Conversation summary and running context") { var summary by remember(context.summary) { mutableStateOf(context.summary.orEmpty()) }; var running by remember(context.runningContext) { mutableStateOf(context.runningContext.orEmpty()) }; OutlinedTextField(summary, { summary = it }, label = { Text("Conversation Summary") }, modifier = Modifier.fillMaxWidth()); TextButton(onClick = { onSummary(summary) }) { Text("Save summary") }; OutlinedTextField(running, { running = it }, label = { Text("Running Context") }, modifier = Modifier.fillMaxWidth()); TextButton(onClick = { onRunning(running) }) { Text("Save running context") } }
@Composable private fun InspectorSection(title: String, body: @Composable ColumnScope.() -> Unit) = Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(title, style = MaterialTheme.typography.titleMedium); body() } }
@Composable private fun CandidateCard(candidate: MemoryCandidate, onApprove: (String) -> Unit, onReject: () -> Unit, onIgnore: () -> Unit) { var edit by remember(candidate.id) { mutableStateOf(candidate.value) }; Card { Column(Modifier.padding(10.dp)) { Text("${candidate.category} · confidence ${(candidate.confidence * 100).toInt()}%", style = MaterialTheme.typography.bodySmall); OutlinedTextField(edit, { edit = it }, label = { Text("Proposed value") }, modifier = Modifier.fillMaxWidth()); Text("Why: ${candidate.explanation}", style = MaterialTheme.typography.bodySmall); Text("Source conversation: ${candidate.conversationId} · source message: ${candidate.supportingMessageId ?: "not supplied"}", style = MaterialTheme.typography.bodySmall); Row { TextButton(onClick = { onApprove(edit) }) { Text("Approve") }; TextButton(onClick = onReject) { Text("Reject") }; TextButton(onClick = onIgnore) { Text("Ignore") } } } } }
@Composable private fun MemoryCard(memory: MemoryRecord, onEdit: (String) -> Unit) { var edit by remember(memory.id, memory.content) { mutableStateOf(memory.content) }; Card { Column(Modifier.padding(10.dp)) { Text(edit); Text("ID ${memory.id} · ${memory.category} · ${memory.status} · confidence ${(memory.confidence * 100).toInt()}%", style = MaterialTheme.typography.bodySmall); Text("Source conversation: ${memory.sourceConversationId ?: "manual"} · source message: ${memory.sourceMessageId ?: "manual"}", style = MaterialTheme.typography.bodySmall); Text("Created: ${memory.createdAtEpochMs} · Updated: ${memory.updatedAtEpochMs}", style = MaterialTheme.typography.bodySmall); OutlinedTextField(edit, { edit = it }, label = { Text("Edit value") }, modifier = Modifier.fillMaxWidth()); TextButton(onClick = { onEdit(edit) }) { Text("Save edit") } } } }
