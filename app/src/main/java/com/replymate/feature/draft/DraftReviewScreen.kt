package com.replymate.feature.draft
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
import com.replymate.core.draft.*
import kotlinx.coroutines.launch
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DraftReviewScreen(app:ReplyMateApplication,draftId:String,onBack:()->Unit){val scope=rememberCoroutineScope();val draft by app.drafts.draft(draftId).collectAsState(initial=null);val versions by app.drafts.versions(draftId).collectAsState(initial=emptyList());val clipboard=LocalClipboardManager.current;var memorySummary by remember{mutableStateOf("")}; LaunchedEffect(draft?.id){draft?.let{ value->memorySummary=runCatching{app.conversationService.memoryForGeneration(value.contactId,value.conversationId).summary.orEmpty()}.getOrDefault("")}};var edit by remember{mutableStateOf("")}; val d=draft?:return; LaunchedEffect(d.reply){edit=d.reply}; Scaffold(topBar={TopAppBar(title={Text("Review draft")},navigationIcon={TextButton(onClick=onBack){Text("Back")}})}){padding->Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)){Text("Incoming message",style=MaterialTheme.typography.titleSmall);Text(d.originalMessage);Text("Contact ${d.contactId} · Conversation ${d.conversationId}",style=MaterialTheme.typography.bodySmall); if(memorySummary.isNotBlank())Text("Memory summary: $memorySummary",style=MaterialTheme.typography.bodySmall);Text("Reasoning: ${d.intent} · ${d.emotion} · ${d.strategy}");Text("Quality ${(d.qualityScore*100).toInt()}% · ${d.tokenEstimate} tokens · ${d.provider} ${d.model} · ${d.generationDurationMs}ms",style=MaterialTheme.typography.bodySmall);Text("Draft",style=MaterialTheme.typography.titleSmall);OutlinedTextField(edit,{edit=it},Modifier.fillMaxWidth(),minLines=4);Row{Button(onClick={scope.launch{app.drafts.edit(d.id,edit)}}){Text("Save edit")};TextButton(onClick={clipboard.setText(AnnotatedString(edit))}){Text("Copy")};TextButton(onClick={scope.launch{app.drafts.status(d.id,DraftStatus.REVIEWED)}}){Text("Mark reviewed")};TextButton(onClick={scope.launch{app.drafts.status(d.id,DraftStatus.DISMISSED)}}){Text("Dismiss")}};Text("Regenerate",style=MaterialTheme.typography.titleSmall);RegenerationStyle.entries.forEach{style->AssistChip(onClick={scope.launch{app.draftGeneration.regenerate(d,style)}},label={Text(style.name.replace('_',' '))})};Text("Draft history",style=MaterialTheme.typography.titleSmall);versions.forEach{Text("${it.action} · ${it.createdAtEpochMs}: ${it.reply}",style=MaterialTheme.typography.bodySmall)};if(d.promptText.isNotBlank()){Text("Prompt used",style=MaterialTheme.typography.titleSmall);SelectionContainer{Text(d.promptText,style=MaterialTheme.typography.bodySmall)}}}}}
