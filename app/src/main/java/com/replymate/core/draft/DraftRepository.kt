package com.replymate.core.draft

import com.replymate.core.conversation.MessagingPlatform
import com.replymate.core.persistence.*
import kotlinx.coroutines.flow.map
import java.util.UUID
class DraftRepository(private val dao:DraftDao){
 fun all()=dao.observeAll().map{it.map(ReplyDraftEntity::toModel)}; fun draft(id:String)=dao.observe(id).map{it?.toModel()}; fun versions(id:String)=dao.observeVersions(id).map{it.map(DraftVersionEntity::toModel)}
 suspend fun save(draft:ReplyDraft,action:String="generated"):ReplyDraft{dao.save(draft.toEntity());dao.saveVersion(DraftVersionEntity(UUID.randomUUID().toString(),draft.id,draft.reply,action));return draft}
 suspend fun edit(id:String,reply:String){dao.updateReply(id,reply,DraftStatus.EDITED.name);dao.saveVersion(DraftVersionEntity(UUID.randomUUID().toString(),id,reply,"edited"))}
 suspend fun status(id:String,status:DraftStatus)=dao.setStatus(id,status.name)
 suspend fun get(id:String)=dao.byId(id)?.toModel()
}
private fun ReplyDraftEntity.toModel()=ReplyDraft(id,MessagingPlatform.valueOf(platform),contactId,conversationId,originalMessage,reply,intent,emotion,strategy,qualityScore,tokenEstimate,generationDurationMs,provider,model,DraftStatus.valueOf(status),promptText,correctiveRegeneration,error,createdAtEpochMs,updatedAtEpochMs)
private fun ReplyDraft.toEntity()=ReplyDraftEntity(id,platform.name,contactId,conversationId,originalMessage,reply,intent,emotion,strategy,qualityScore,tokenEstimate,generationDurationMs,provider,model,status.name,promptText,correctiveRegeneration,error,createdAtEpochMs.takeIf{it>0}?:System.currentTimeMillis(),System.currentTimeMillis())
private fun DraftVersionEntity.toModel()=DraftVersion(id,draftId,reply,action,createdAtEpochMs)
