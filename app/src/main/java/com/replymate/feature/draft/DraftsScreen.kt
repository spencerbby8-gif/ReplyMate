package com.replymate.feature.draft
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.replymate.ReplyMateApplication
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun DraftsScreen(app:ReplyMateApplication,onOpen:(String)->Unit){val drafts by app.drafts.all().collectAsState(initial=emptyList()); Scaffold(topBar={TopAppBar(title={Text("AI Drafts")})}){padding->Column(Modifier.padding(padding)){if(drafts.isEmpty())Text("No drafts yet.",Modifier.padding(20.dp)) else drafts.forEach{draft->ListItem(headlineContent={Text(draft.reply.ifBlank{"Generation failed"})},supportingContent={Text("${draft.platform} · ${draft.status} · ${draft.strategy}")},modifier=Modifier.fillMaxWidth().clickable{onOpen(draft.id)})}}}}
