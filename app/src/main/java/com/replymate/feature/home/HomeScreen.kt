package com.replymate.feature.home
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun HomeScreen(onPersonalization:()->Unit,onSettings:()->Unit,onDrafts:()->Unit){Scaffold(topBar={TopAppBar(title={Text("ReplyMate")},actions={TextButton(onClick=onSettings){Text("Settings")}})},bottomBar={NavigationBar{NavigationBarItem(selected=true,onClick={},icon={},label={Text("Home")});NavigationBarItem(selected=false,onClick=onDrafts,icon={},label={Text("AI Drafts")})}}){padding->Column(Modifier.padding(padding).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)){Text("Your private reply workspace",style=MaterialTheme.typography.headlineSmall);Card{Column(Modifier.padding(16.dp)){Text("Draft review is always required",style=MaterialTheme.typography.titleMedium);Text("Supported notifications can create drafts, but ReplyMate never sends a message automatically.")}};Button(onClick=onDrafts,modifier=Modifier.fillMaxWidth()){Text("Review AI drafts")};Button(onClick=onPersonalization,modifier=Modifier.fillMaxWidth()){Text("Set up my voice")}}}}
