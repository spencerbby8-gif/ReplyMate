package com.replymate.core.draft

import com.replymate.core.ai.AiProviderSettingsRepository
import com.replymate.core.conversation.ConversationService
import com.replymate.core.conversation.MemoryContext
import com.replymate.core.conversation.MessagingPlatform
import com.replymate.core.model.Personalization
import com.replymate.core.persistence.PersonalizationRepository
import com.replymate.core.reasoning.ReasonedResponsePipeline
import kotlinx.coroutines.flow.first
import java.util.UUID

/** Creates review-only drafts from normalized events. It never sends through a messaging platform. */
class DraftGenerationService(private val conversations:ConversationService,private val personalization:PersonalizationRepository,private val providerSettings:AiProviderSettingsRepository,private val reasoning:ReasonedResponsePipeline,private val drafts:DraftRepository){
 suspend fun generate(platform:MessagingPlatform,contactId:String,conversationId:String,incomingMessage:String,styleInstruction:String=""):ReplyDraft {
  val memory=conversations.memoryForGeneration(contactId,conversationId); val profile=personalization.personalization.first(); val settings=providerSettings.settings.first()
  val base="You are ReplyMate. Produce a private draft for the account owner to review; never claim it was sent. $styleInstruction"
  val output=reasoning.generate(base,profile,memory,incomingMessage,settings)
  val draft=output.fold(onSuccess={result->ReplyDraft(UUID.randomUUID().toString(),platform,contactId,conversationId,incomingMessage,result.reply,result.plan.intents.joinToString{it.label.name},result.plan.emotion.label.name,result.plan.strategy.name,result.quality.estimatedConfidence,result.preparedPrompt.estimatedTokens,result.generationDurationMs,result.provider,result.model,DraftStatus.GENERATED,result.preparedPrompt.text,result.regenerated)},onFailure={error->ReplyDraft(UUID.randomUUID().toString(),platform,contactId,conversationId,incomingMessage,"","","","",0f,0,0,"","",DraftStatus.FAILED,"",false,error.message)})
  return drafts.save(draft)
 }
 suspend fun regenerate(existing:ReplyDraft,style:RegenerationStyle)=generate(existing.platform,existing.contactId,existing.conversationId,existing.originalMessage,style.instruction)
}
