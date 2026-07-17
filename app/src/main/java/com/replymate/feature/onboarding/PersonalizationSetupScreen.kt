package com.replymate.feature.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.replymate.core.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PersonalizationSetupScreen(value: Personalization, onChange: (Personalization) -> Unit, onReset: () -> Unit, onPreview: () -> Unit, onFinish: () -> Unit) {
    var showResetConfirmation by remember { mutableStateOf(false) }
    if (showResetConfirmation) AlertDialog(
        onDismissRequest = { showResetConfirmation = false },
        title = { Text("Reset voice setup?") },
        text = { Text("This removes your profile, global writing style, custom prompt, and per-contact style rules. Conversation history and contact memory are not changed.") },
        confirmButton = { TextButton(onClick = { onReset(); showResetConfirmation = false }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { showResetConfirmation = false }) { Text("Cancel") } }
    )
    Scaffold(topBar = { TopAppBar(title = { Text("Your voice") }) }, bottomBar = {
        Surface(tonalElevation = 3.dp) { Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { showResetConfirmation = true }, modifier = Modifier.weight(1f)) { Text("Reset") }
            Button(onClick = onFinish, modifier = Modifier.weight(1f)) { Text("Save & continue") }
        } }
    }) { padding -> Column(Modifier.padding(padding).padding(horizontal = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Personalize ReplyMate", style = MaterialTheme.typography.headlineSmall)
        Text("Only enter what you want Gemini to use. Blank fields stay blank—ReplyMate never invents your personality or story.", style = MaterialTheme.typography.bodyMedium)
        ProfileEditor(value.profile) { onChange(value.copy(profile = it)) }
        HorizontalDivider(); Text("Global writing style", style = MaterialTheme.typography.titleLarge)
        StyleEditor(value.globalStyle) { onChange(value.copy(globalStyle = it)) }
        HorizontalDivider(); Text("Custom prompt", style = MaterialTheme.typography.titleLarge)
        Text("Your instructions are added after your global style and before contact-specific rules.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value.customPrompt, { onChange(value.copy(customPrompt = it)) }, label = { Text("Instructions for Gemini") }, minLines = 4, modifier = Modifier.fillMaxWidth())
        OutlinedButton(onClick = onPreview, modifier = Modifier.fillMaxWidth()) { Text("Preview final prompt") }
        Spacer(Modifier.height(80.dp))
    } }
}

@Composable private fun ProfileEditor(value: MyProfile, change: (MyProfile) -> Unit) {
    Text("My profile", style = MaterialTheme.typography.titleLarge)
    Field("Name or nickname", value.nameOrNickname) { change(value.copy(nameOrNickname = it)) }
    Field("Personality", value.personality) { change(value.copy(personality = it)) }
    Field("Tone in my own words", value.additionalContext) { change(value.copy(additionalContext = it)) }
    Field("Interests", value.interests) { change(value.copy(interests = it)) }
    Field("Habits", value.habits) { change(value.copy(habits = it)) }
    Field("Background story", value.backgroundStory, 3) { change(value.copy(backgroundStory = it)) }
    Field("Relationship style", value.relationshipStyle) { change(value.copy(relationshipStyle = it)) }
}
@Composable private fun Field(label: String, value: String, lines: Int = 1, onValue: (String) -> Unit) = OutlinedTextField(value, onValue, Modifier.fillMaxWidth(), label = { Text(label) }, minLines = lines)

@Composable private fun StyleEditor(value: WritingStyle, change: (WritingStyle) -> Unit) {
    EnumSelect("Formality", value.formality, Formality.entries) { change(value.copy(formality = it)) }
    EnumSelect("Reply length", value.detailLevel, DetailLevel.entries) { change(value.copy(detailLevel = it)) }
    EnumSelect("Humor", value.humorLevel, HumorLevel.entries) { change(value.copy(humorLevel = it)) }
    EnumSelect("Directness", value.directness, Directness.entries) { change(value.copy(directness = it)) }
    EnumSelect("Flirty vs neutral", value.flirtiness, Flirtiness.entries) { change(value.copy(flirtiness = it)) }
    EnumSelect("Emoji usage", value.emojiUsage, UsageLevel.entries) { change(value.copy(emojiUsage = it)) }
    EnumSelect("Slang usage", value.slangUsage, UsageLevel.entries) { change(value.copy(slangUsage = it)) }
    Field("Greeting style", value.greetingStyle) { change(value.copy(greetingStyle = it)) }
    Field("Closing style", value.closingStyle) { change(value.copy(closingStyle = it)) }
}
@Composable private fun <T : Enum<T>> EnumSelect(label: String, selected: T, values: List<T>, choose: (T) -> Unit) {
    var expanded = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded.value, { expanded.value = it }) {
        OutlinedTextField(selected.name.replace('_', ' '), {}, Modifier.menuAnchor().fillMaxWidth(), readOnly = true, label = { Text(label) }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded.value) })
        ExposedDropdownMenu(expanded.value, { expanded.value = false }) { values.forEach { item -> DropdownMenuItem({ Text(item.name.replace('_', ' ')) }, { choose(item); expanded.value = false }) } }
    }
}
