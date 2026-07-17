package com.replymate.feature.ai

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.replymate.ReplyMateApplication
import com.replymate.core.ai.ConnectionResult
import com.replymate.core.ai.DEFAULT_GEMINI_MODEL
import com.replymate.core.network.NetworkStatus
import kotlinx.coroutines.launch

private sealed interface KeyTestState { data object Idle : KeyTestState; data object Testing : KeyTestState; data object Success : KeyTestState; data class Error(val message: String, val retryable: Boolean) : KeyTestState }

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun GeminiApiKeyScreen(app: ReplyMateApplication, model: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val network by app.networkMonitor.status.collectAsState()
    var key by remember { mutableStateOf("") }
    var showKey by remember { mutableStateOf(false) }
    var testState by remember { mutableStateOf<KeyTestState>(KeyTestState.Idle) }
    var selectedModel by remember(model) { mutableStateOf(model.ifBlank { DEFAULT_GEMINI_MODEL }) }
    fun saveAndTest() {
        testState = KeyTestState.Testing
        scope.launch {
            testState = when (val result = app.aiService.testGeminiConnection(selectedModel, key.takeIf { it.isNotBlank() })) {
                ConnectionResult.Success -> { if (key.isNotBlank()) app.apiKeys.saveGeminiKey(key); app.aiProviderSettings.setGeminiModel(selectedModel); KeyTestState.Success }
                is ConnectionResult.Failure -> KeyTestState.Error(result.userMessage, result.retryable)
            }
        }
    }
    Scaffold(topBar = { TopAppBar(title = { Text("Gemini API key") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        Column(Modifier.padding(padding).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("ReplyMate stores your key only on this device using Android Keystore-backed encrypted storage.", style = MaterialTheme.typography.bodyMedium)
            app.apiKeys.maskedGeminiKey()?.let { Text("Saved key: $it", style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(key, { key = it; testState = KeyTestState.Idle }, Modifier.fillMaxWidth(), label = { Text("Gemini API key") }, singleLine = true, visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(), trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "Hide" else "Show") } })
            OutlinedTextField(selectedModel, { selectedModel = it; testState = KeyTestState.Idle }, Modifier.fillMaxWidth(), label = { Text("Gemini model") }, singleLine = true, supportingText = { Text("Model IDs may change; edit this value to match your Gemini account.") })
            if (network == NetworkStatus.Unavailable) AssistChip(onClick = {}, label = { Text("Offline — connection testing is unavailable") })
            when (val state = testState) {
                KeyTestState.Testing -> Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { CircularProgressIndicator(Modifier.size(20.dp)); Text("Testing connection…") }
                KeyTestState.Success -> Text("Connection successful. Your key is saved securely.", color = MaterialTheme.colorScheme.primary)
                is KeyTestState.Error -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text(state.message, color = MaterialTheme.colorScheme.error); if (state.retryable) OutlinedButton(onClick = ::saveAndTest) { Text("Retry") } }
                KeyTestState.Idle -> Unit
            }
            Button(onClick = ::saveAndTest, enabled = testState != KeyTestState.Testing && (key.isNotBlank() || app.apiKeys.isGeminiConfigured()) && network != NetworkStatus.Unavailable, modifier = Modifier.fillMaxWidth()) { Text("Save and test connection") }
            Text("Reply generation is not enabled in this slice. The test only checks Gemini connectivity; it does not send messages or prompts.", style = MaterialTheme.typography.bodySmall)
        }
    }
}
