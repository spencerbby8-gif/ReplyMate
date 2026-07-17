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
import com.replymate.core.conversation.*
import com.replymate.core.reasoning.*
import kotlinx.coroutines.launch
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun AiPlaygroundScreen(app: ReplyMateApplication, personalization: Personalization, model: String, onEditPersonalization: () -> Unit, onBack: () -> Unit) {
    val scope = rememberCoroutineScope(); val contacts by app.playground.contacts.collectAsState(initial = emptyList())
    var selected by remember { mutableStateOf(PlaygroundContact(id = "")) }; var testMessage by remember { mutableStateOf("") }
    var activeContact by remember { mutableStateOf<Contact?>(null) }; var activeConversation by remember { mutableStateOf<Conversation?>(null) }; var retrievedMemory by remember { mutableStateOf<MemoryContext?>(null) }
    var prepared by remember { mutableStateOf<PreparedPrompt?>(null) }; var reasoned by remember { mutableStateOf<ReasonedDraft?>(null) }; var showInspector by remember { mutableStateOf(false) }; var outcome by remember { mutableStateOf<PlaygroundGenerationOutcome?>(null) }
    val generations by app.playground.generations(selected.id).collectAsState(initial = emptyList())
    val network by app.networkMonitor.status.collectAsState()
    fun request() = selected.toPromptRequest(personalization, testMessage, retrievedMemory)
    fun prepare() { prepared = app.promptPipeline.prepare(request()); outcome = null }
    fun saveContact() = scope.launch {
        selected = app.playground.save(selected)
        val pair = app.conversationService.createOrOpen(MessagingPlatform.PLAYGROUND, "playground:${selected.id}", selected.name, "playground-conversation:${selected.id}", selected.nickname, selected.relationship, selected.personality, selected.communicationStyle)
        activeContact = pair.first; activeConversation = pair.second
        selected.conversationSummary.takeIf { it.isNotBlank() }?.let { app.memoryInspector.propose(pair.first.id, pair.second.id, MemoryCategory.LONG_TERM_MEMORY, "Conversation summary: $it", null, "User entered this Playground summary", 1f) }
        selected.importantFacts.lines().filter { it.isNotBlank() }.forEach { app.memoryInspector.propose(pair.first.id, pair.second.id, MemoryCategory.IMPORTANT_FACT, it, null, "User entered this Playground fact", 1f) }
        selected.preferences.lines().filter { it.isNotBlank() }.forEach { app.memoryInspector.propose(pair.first.id, pair.second.id, MemoryCategory.PREFERENCE, it, null, "User entered this Playground preference", 1f) }
        selected.longTermMemory.lines().filter { it.isNotBlank() }.forEach { app.memoryInspector.propose(pair.first.id, pair.second.id, MemoryCategory.LONG_TERM_MEMORY, it, null, "User entered this Playground note", 1f) }
        retrievedMemory = app.conversationService.memoryForGeneration(pair.first.id, pair.second.id)
    }
    fun simulateIncoming() = scope.launch {
        val contact = activeContact ?: return@launch; val conversation = activeConversation ?: return@launch
        if (testMessage.isNotBlank()) { app.conversationService.recordTurn(contact.id, conversation.id, MessageDirection.INCOMING, testMessage); retrievedMemory = app.conversationService.memoryForGeneration(contact.id, conversation.id); prepared = null }
    }
    fun generate() {
        val memory = retrievedMemory ?: return
        outcome = null; reasoned = null
        scope.launch {
            val result = app.reasoningPipeline.generate("You are ReplyMate's internal AI Playground. Draft a natural reply in the user's voice. This is a test only; never state that a message was sent.", personalization, memory, testMessage, AiProviderSettings(model = model))
            result.onSuccess { draft ->
                reasoned = draft; prepared = draft.preparedPrompt
                activeContact?.let { contact -> activeConversation?.let { conversation -> app.conversationService.recordTurn(contact.id, conversation.id, MessageDirection.OUTGOING, draft.reply); retrievedMemory = app.conversationService.memoryForGeneration(contact.id, conversation.id) } }
                if (selected.id.isNotBlank()) app.playground.saveGeneration(PlaygroundGenerationEntity(UUID.randomUUID().toString(), selected.id, draft.reply, draft.provider, draft.model, draft.generationDurationMs, prepared?.estimatedTokens ?: 0, prepared?.omittedHistoryTurns ?: 0, prepared?.omittedMemoryItems ?: 0))
            }.onFailure { outcome = PlaygroundGenerationOutcome.Failure(ConnectionResult.Failure(AiErrorCategory.UNKNOWN, it.message ?: "Reasoning pipeline failed.", true)) }
        }
    }
    if (showInspector && activeContact != null && activeConversation != null) { MemoryInspectorScreen(app, activeContact!!.id, activeConversation!!.id, onBack = { showInspector = false }); return }
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
            Section("Memory editor") { MemoryEditor(selected) { selected = it; prepared = null }; if (activeConversation != null) OutlinedButton(onClick = ::simulateIncoming) { Text("Simulate incoming message") } }
            retrievedMemory?.let { MemoryInspection(it); OutlinedButton(onClick = { showInspector = true }) { Text("Open Memory Inspector") } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = ::prepare) { Text("Build prompt") }; Button(onClick = ::generate, enabled = testMessage.isNotBlank() && retrievedMemory != null && (prepared?.fitsBudget != false)) { Text("Generate in Playground") } }
            prepared?.let { PromptPreview(it) }
            reasoned?.let { ReasoningPanel(it) }
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
@Composable private fun MemoryInspection(context: MemoryContext) = Section("Retrieved conversation context") { Text("Contact ID: ${context.contact.id}", style = MaterialTheme.typography.bodySmall); Text("Recent turns: ${context.recentMessages.size} · Facts: ${context.importantFacts.size} · Preferences: ${context.preferences.size} · Long-term notes: ${context.longTermMemory.size}"); context.summary?.let { Text("Summary: $it") }; context.runningContext?.let { Text("Running context: $it") }; context.promptMemory().forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) } }
@Composable private fun ReasoningPanel(draft: ReasonedDraft) = Section("Response pipeline") { Text("Intent: ${draft.plan.intents.joinToString { it.label.name }}"); Text("Emotion: ${draft.plan.emotion.label} (${(draft.plan.emotion.confidence * 100).toInt()}%)"); Text("Relationship: ${draft.plan.relationship.label ?: "Unknown"}"); Text("Goal: ${draft.plan.goal} · Strategy: ${draft.plan.strategy}"); Text(draft.plan.strategyExplanation, style = MaterialTheme.typography.bodySmall); Text("Final reply", style = MaterialTheme.typography.titleSmall); Text(draft.reply); Text("Quality — style ${(draft.quality.styleMatch * 100).toInt()} · relevance ${(draft.quality.relevance * 100).toInt()} · naturalness ${(draft.quality.naturalness * 100).toInt()} · confidence ${(draft.quality.estimatedConfidence * 100).toInt()}%", style = MaterialTheme.typography.bodySmall); if(draft.quality.concerns.isNotEmpty()) Text("Quality notes: ${draft.quality.concerns.joinToString()}", style = MaterialTheme.typography.bodySmall); Text("${draft.provider} · ${draft.model} · ${draft.generationDurationMs} ms${if(draft.regenerated) " · regenerated once" else ""}", style = MaterialTheme.typography.bodySmall) }
@Composable private fun PlaygroundOutcome(outcome: PlaygroundGenerationOutcome?, onRetry: () -> Unit) { when (outcome) { null -> Unit; is PlaygroundGenerationOutcome.Success -> Section("Generated reply") { SelectionContainer { Text(outcome.result.text) }; Text("${outcome.result.provider} · ${outcome.result.model} · ${outcome.result.durationMs} ms", style = MaterialTheme.typography.bodySmall) }; is PlaygroundGenerationOutcome.Failure -> Section("Generation failed") { Text(outcome.error.userMessage, color = MaterialTheme.colorScheme.error); if (outcome.error.retryable) OutlinedButton(onClick = onRetry) { Text("Retry") } } } }
@Composable private fun GenerationHistory(items: List<PlaygroundGenerationEntity>, onRegenerate: () -> Unit, onDelete: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current; var compareId by remember { mutableStateOf<String?>(null) }
    Section("Reply history") {
        if (items.isEmpty()) {
            Text("No saved generations for this test contact yet.")
        } else {
            items.forEach { item ->
                Card {
                    Column(Modifier.padding(10.dp)) {
                        Text(item.reply)
                        Text("${item.provider} · ${item.model} · ${item.durationMs} ms", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { clipboard.setText(AnnotatedString(item.reply)) }) { Text("Copy") }
                            TextButton(onClick = { compareId = if (compareId == item.id) null else item.id }) { Text(if (compareId == item.id) "Comparing" else "Compare") }
                            TextButton(onClick = { onDelete(item.id) }) { Text("Delete") }
                        }
                    }
                }
            }
            compareId?.let { id -> items.firstOrNull { it.id == id }?.let { compared -> Card { Column(Modifier.padding(10.dp)) { Text("Comparison target", style = MaterialTheme.typography.labelLarge); Text(compared.reply) } } } }
            OutlinedButton(onClick = onRegenerate) { Text("Regenerate current test") }
        }
    }
}
private fun PlaygroundContact.toPromptRequest(personalization: Personalization, latest: String, retrieved: MemoryContext?): PromptRequest = PromptRequest(
    baseSystemPrompt = "You are ReplyMate's internal AI Playground. Draft a natural reply in the user's voice. This is a test only; never state that a message was sent.", personalization = personalization,
    contactRule = ContactStyleRule(id, relationshipContext = listOf("Relationship: $relationship", "Nickname: $nickname", "Personality: $personality").filterNot { it.endsWith(": ") }.joinToString("\n"), customInstructions = communicationStyle),
    recentHistory = retrieved?.asTurns() ?: recentHistory.lines().filter { it.isNotBlank() }.map { line -> if (line.trimStart().startsWith("Me:", true)) ConversationTurn(Speaker.ME, line.substringAfter(':').trim()) else ConversationTurn(Speaker.CONTACT, line.substringAfter(':', line).trim()) },
    contactMemory = retrieved?.promptMemory() ?: buildList { conversationSummary.takeIf { it.isNotBlank() }?.let { add("Conversation summary: $it") }; importantFacts.lines().filter { it.isNotBlank() }.forEach { add("Important fact: $it") }; preferences.lines().filter { it.isNotBlank() }.forEach { add("Preference: $it") }; longTermMemory.lines().filter { it.isNotBlank() }.forEach { add("Long-term memory: $it") } }, latestIncomingMessage = latest)
