package com.replymate.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun SettingsScreen(onPersonalization: () -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { padding ->
        Column(Modifier.padding(padding)) {
            SettingRow("Personalization", "Profile, global writing style, custom prompt", onPersonalization)
            SettingRow("API keys", "Not configured in Phase 1", {})
            SettingRow("Notification behavior", "Available in a later phase", {})
            SettingRow("Memory management", "Available in a later phase", {})
            SettingRow("Data export", "Available in a later phase", {})
        }
    }
}
@Composable private fun SettingRow(title: String, subtitle: String, action: () -> Unit) = ListItem(headlineContent = { Text(title) }, supportingContent = { Text(subtitle) }, modifier = Modifier.fillMaxWidth().clickable(onClick = action))
