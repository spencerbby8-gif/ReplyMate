package com.replymate.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(primary = Color(0xFF365F91), secondary = Color(0xFF526780), tertiary = Color(0xFF6C577B))
private val Dark = darkColorScheme(primary = Color(0xFFA8C8FF), secondary = Color(0xFFBAC8E2), tertiary = Color(0xFFD9BDE9))
@Composable fun ReplyMateTheme(darkTheme: Boolean = false, content: @Composable () -> Unit) = MaterialTheme(colorScheme = if (darkTheme) Dark else Light, content = content)
