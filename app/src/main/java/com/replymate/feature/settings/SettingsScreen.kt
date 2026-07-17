package com.replymate.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.replymate.core.settings.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SettingsScreen(theme: AppTheme, geminiConfigured: Boolean, onThemeChanged: (AppTheme) -> Unit, onPersonalization: () -> Unit, onGeminiSettings: () -> Unit, onPlayground: () -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        Column(Modifier.padding(padding)) {
            Text("Appearance", Modifier.padding(16.dp, 16.dp, 16.dp, 4.dp), style = MaterialTheme.typography.titleMedium)
            AppTheme.entries.forEach { option -> ListItem(headlineContent = { Text(option.name.lowercase().replaceFirstChar { it.uppercase() }) }, trailingContent = { RadioButton(selected = option == theme, onClick = { onThemeChanged(option) }) }, modifier = Modifier.fillMaxWidth().clickable { onThemeChanged(option) }) }
            HorizontalDivider()
            SettingRow("Personalization", "Profile, global writing style, custom prompt", onPersonalization)
            SettingRow("Gemini", if (geminiConfigured) "API key configured on this device" else "API key not configured", onGeminiSettings)
            SettingRow("AI Playground", "Internal prompt, memory, and generation test tool", onPlayground)
            SettingRow("Notification access", "Configure through Android system settings", {})
            SettingRow("Notification behavior", "Available in a later slice", {})
            SettingRow("Memory management", "Available in a later slice", {})
            SettingRow("Data export", "Available in a later slice", {})
        }
    }
}
@Composable private fun SettingRow(title: String, subtitle: String, action: () -> Unit) = ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) }, modifier = Modifier.fillMaxWidth().clickable(onClick = action))
