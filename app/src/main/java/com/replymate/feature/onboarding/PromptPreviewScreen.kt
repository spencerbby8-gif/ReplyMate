package com.replymate.feature.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PromptPreviewScreen(prompt: String, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Prompt preview") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("This is the assembled instruction sent with the selected conversation context. API keys are never included.", style = MaterialTheme.typography.bodyMedium)
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.medium) {
                SelectionContainer { Text(prompt, Modifier.padding(16.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}
