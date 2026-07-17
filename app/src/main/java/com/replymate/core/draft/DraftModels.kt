package com.replymate.core.draft

import com.replymate.core.conversation.MessagingPlatform

enum class DraftStatus { GENERATED, EDITED, REVIEWED, DISMISSED, FAILED }
enum class RegenerationStyle(val instruction: String) { SHORTER("Make the reply shorter."), LONGER("Make the reply more detailed."), MORE_CASUAL("Use a more casual tone."), MORE_FORMAL("Use a more formal tone."), MORE_PLAYFUL("Make the reply more playful."), MORE_EMPATHETIC("Make the reply more empathetic."), MORE_DIRECT("Make the reply more direct.") }
data class ReplyDraft(val id:String,val platform:MessagingPlatform,val contactId:String,val conversationId:String,val originalMessage:String,val reply:String,val intent:String,val emotion:String,val strategy:String,val qualityScore:Float,val tokenEstimate:Int,val generationDurationMs:Long,val provider:String,val model:String,val status:DraftStatus,val promptText:String,val correctiveRegeneration:Boolean,val error:String?=null,val createdAtEpochMs:Long=0L,val updatedAtEpochMs:Long=0L)
data class DraftVersion(val id:String,val draftId:String,val reply:String,val action:String,val createdAtEpochMs:Long)
