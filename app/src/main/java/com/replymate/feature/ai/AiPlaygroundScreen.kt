package com.replymate.feature.ai

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.replymate.ReplyMateApplication
import com.replymate.core.ai.*
import com.replymate.core.model.*
import com.replymate.core.persistence.PlaygroundGenerationEntity
import com.replymate.core.network.NetworkStatus
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AiPlaygroundScreen(app: ReplyMateApplication, personalization: Personalization, model: String, onEditPersonalization: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope(); val contacts by app.playground.contacts.collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf(PlaygroundContact(id = "")) }; var testMessage by remember { mutableStateOf("") }
    var prepared by remember { mutableStateOf<PreparedPrompt?>(null) }; var outcome by remember { mutableStateOf<PlaygroundGenerationOutcome?>(null) }
    val generations by app.playground.generations(selected.id).collectAsState(initial = emptyList())
    val network by app.networkMonitor.status.collectAsState()
    fun prepare() { prepared = app.promptPipeline.prepare(selected.toPromptRequest(personalization, testMessage)); outcome = null }
    fun saveContact() = scope.launch { selected = app.playground.save(selected) }
    fun generate() { val current = prepared ?: app.promptPipeline.prepare(selected.toPromptRequest(personalization, testMessage)).also { prepared = it }; outcome = null; scope.launch { when (val result = app.playgroundGeneration.generate(current, AiProviderSettings(model = model))) { is PlaygroundGenerationOutcome.Success -> { outcome = result; if (selected.id.isNotBlank()) app.playground.saveGeneration(PlaygroundGenerationEntity(UUID.randomUUID().toString(), selected.id, result.result.text, result.result.provider.name, result.result.model, result.result.durationMs, current.estimatedTokens, current.omittedHistoryTurns, current.omittedMemoryItems)) }; is PlaygroundGenerationOutcome.Failure -> outcome = result } } }
    Scaffold(topBar = { TopAppBar(title = { Text("AI Playground") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        Column(Modifier.padding(padding).padding(horizontal = 16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Development tool", style = MaterialTheme.typography.titleMedium); Text("Playground contacts and generations are isolated from real messaging. Nothing here sends a message.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onEditPersonalization, modifier = Modifier.fillMaxWidth()) { Text("Edit My Profile, writing style & custom prompt") }
            Section("Test message") { OutlinedTextField(testMessage, { testMessage = it; prepared = null }, Modifier.fillMaxWidth(), label = { Text("Incoming message") }, minLines = 3) }
            Section("Fake contact") {
                if (contacts.isNotEmpty()) contacts.forEach { contact -> FilterChip(selected = selected.id == contact.id, onClick = { selected = contact; prepared = null }, label = { Text(contact.name.ifBlank { "Unnamed contact" }) }) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { selected = PlaygroundContact(""); prepared = null }) { Text("New") }; Button(onClick = ::saveContact) { Text("Save test contact") } }
                PlaygroundContactEditor(selected) { selected = it; prepared = null }
            }
            Section("Memory editor") { MemoryEditor(selected) { selected = it; prepared = null } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = ::prepare) { Text("Build prompt") }; Button(onClick = ::generate, enabled = testMessage.isNotBlank() && (prepared?.fitsBudget != false)) { Text("Generate in Playground") } }
            prepared?.let { PromptPreview(it) }
            PlaygroundOutcome(outcome, onRetry = ::generate)
            Section("Safe diagnostics") { Text("Provider: Gemini · Model: $model"); Text("Network: ${if (network == NetworkStatus.Available) "available" else "offline"}"); Text("Diagnostics intentionally exclude keys, prompts, messages, and raw provider responses.", style = MaterialTheme.typography.bodySmall) }
            if (selected.id.isNotBlank()) GenerationHistory(generations, onRegenerate = ::generate, onDelete = { id -> scope.launch { app.playground.deleteGeneration(id) } })
            Spacer(Modifier.height(32.dp))
        }
    }
}
@Composable private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) = Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = { Text(title, style = MaterialTheme.typography.titleMedium); content() }) }
@Composable private fun PlaygroundContactEditor(value: PlaygroundContact, change: (PlaygroundContact) -> Unit) {
    SmallField("Name", value.name) { change(value.copy(name = it)) }; SmallField("Relationship", value.relationship) { change(value.copy(relationship = it)) }; SmallField("Nickname", value.nickname) { change(value.copy(nickname = it)) }; SmallField("Personality", value.personality) { change(value.copy(personality = it)) }; SmallField("Communication style", value.communicationStyle) { change(value.copy(communicationStyle = it)) }
}
@Composable private fun MemoryEditor(value: PlaygroundContact, change: (PlaygroundContact) -> Unit) {
    SmallField("Conversation summary", value.conversationSummary, 3) { change(value.copy(conversationSummary = it)) }; SmallField("Important facts (one per line)", value.importantFacts, 3) { change(value.copy(importantFacts = it)) }; SmallField("Preferences (one per line)", value.preferences, 3) { change(value.copy(preferences = it)) }; SmallField("Long-term memory (one per line)", value.longTermMemory, 3) { change(value.copy(longTermMemory = it)) }; SmallField("Recent history (Me: or Contact: one turn per line)", value.recentHistory, 4) { change(value.copy(recentHistory = it)) }
}
@Composable private fun SmallField(label: String, value: String, lines: Int = 1, onChange: (String) -> Unit) = OutlinedTextField(value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, minLines = lines)
@Composable private fun PromptPreview(prepared: PreparedPrompt) = Section("Prompt preview") {
    Text("Estimated tokens: ${prepared.estimatedTokens}"); Text("Optimization: removed ${prepared.omittedMemoryItems} memory item(s), ${prepared.omittedHistoryTurns} history turn(s). ${if (prepared.fitsBudget) "Fits budget" else "Over budget"}", style = MaterialTheme.typography.bodySmall)
    prepared.sections.forEach { section -> Text(section.title, style = MaterialTheme.typography.labelLarge); SelectionContainer { Text(section.content, style = MaterialTheme.typography.bodySmall) }; HorizontalDivider() }
}
@Composable private fun PlaygroundOutcome(outcome: PlaygroundGenerationOutcome?, onRetry: () -> Unit) { when (outcome) { null -> Unit; is PlaygroundGenerationOutcome.Success -> Section("Generated reply") { SelectionContainer { Text(outcome.result.text) }; Text("${outcome.result.provider} · ${outcome.result.model} · ${outcome.result.durationMs} ms", style = MaterialTheme.typography.bodySmall) }; is PlaygroundGenerationOutcome.Failure -> Section("Generation failed") { Text(outcome.error.userMessage, color = MaterialTheme.colorScheme.error); if (outcome.error.retryable) OutlinedButton(onClick = onRetry) { Text("Retry") } } } }
@Composable private fun GenerationHistory(items: List<PlaygroundGenerationEntity>, onRegenerate: () -> Unit, onDelete: (String) -> Unit) { val clipboard = LocalClipboardManager.current; var compareId by remember { mutableStateOf<String?>(null) }; Section("Reply history") { if (items.isEmpty()) Text("No saved generations for this test contact yet.") else { items.forEach { item -> Card { Column(Modifier.padding(10.dp)) { Text(item.reply); Text("${item.provider} · ${item.model} · ${item.durationMs} ms", style = MaterialTheme.typography.bodySmall); Row { TextButton(onClick = { clipboard.setText(AnnotatedString(item.reply)) }) { Text("Copy") }; TextButton(onClick = { compareId = if (compareId == item.id) null else item.id }) { Text(if (compareId == item.id) "Comparing" else "Compare") }; TextButton(onClick = { onDelete(item.id) }) { Text("Delete") } } } } }; compareId?.let { id -> items.firstOrNull { it.id == id }?.let { compared -> Card { Column(Modifier.padding(10.dp)) { Text("Comparison target", style = MaterialTheme.typography.labelLarge); Text(compared.reply) } } } }; OutlinedButton(onClick = onRegenerate) { Text("Regenerate current test") } } }
private fun PlaygroundContact.toPromptRequest(personalization: Personalization, latest: String): PromptRequest = PromptRequest(
    baseSystemPrompt = "You are ReplyMate's internal AI Playground. Draft a natural reply in the user's voice. This is a test only; never state that a message was sent.", personalization = personalization,
    contactRule = ContactStyleRule(id, relationshipContext = listOf("Relationship: $relationship", "Nickname: $nickname", "Personality: $personality").filterNot { it.endsWith(": ") }.joinToString("\n"), customInstructions = communicationStyle),
    recentHistory = recentHistory.lines().filter { it.isNotBlank() }.map { line -> if (line.trimStart().startsWith("Me:", true)) ConversationTurn(Speaker.ME, line.substringAfter(':').trim()) else ConversationTurn(Speaker.CONTACT, line.substringAfter(':', line).trim()) },
    contactMemory = buildList { conversationSummary.takeIf { it.isNotBlank() }?.let { add("Conversation summary: $it") }; importantFacts.lines().filter { it.isNotBlank() }.forEach { add("Important fact: $it") }; preferences.lines().filter { it.isNotBlank() }.forEach { add("Preference: $it") }; longTermMemory.lines().filter { it.isNotBlank() }.forEach { add("Long-term memory: $it") } }, latestIncomingMessage = latest)
