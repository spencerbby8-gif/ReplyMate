package com.replymate.core.platform

import android.service.notification.StatusBarNotification
import com.replymate.core.conversation.*
import com.replymate.core.persistence.PlatformEventDao
import com.replymate.core.persistence.PlatformEventEntity
import com.replymate.core.draft.DraftGenerationService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.util.UUID

class EventValidator {
    fun validate(sbn: StatusBarNotification): EventRejection? { val n=sbn.notification; return when { n.isSilent -> EventRejection.SILENT_NOTIFICATION; (n.flags and android.app.Notification.FLAG_GROUP_SUMMARY) != 0 -> EventRejection.GROUP_SUMMARY; n.extras.getCharSequence("android.title") == null && n.extras.getParcelableArray("android.messages") == null -> EventRejection.UNSUPPORTED_FORMAT; else -> null } }
}
class PlatformManager(adapters: Set<PlatformAdapter>) { private val byPackage=adapters.associateBy { it.packageName }; fun adapter(packageName:String)=byPackage[packageName] }
class PlatformEventQueue(private val dao: PlatformEventDao) {
    fun history()=dao.observeHistory(); suspend fun statistics()=mapOf("queued" to dao.count(EventQueueStatus.QUEUED.name),"processing" to dao.count(EventQueueStatus.PROCESSING.name),"processed" to dao.count(EventQueueStatus.PROCESSED.name),"failed" to dao.count(EventQueueStatus.FAILED.name),"duplicate" to dao.count(EventQueueStatus.DUPLICATE.name),"rejected" to dao.count(EventQueueStatus.REJECTED.name))
    suspend fun enqueue(event: StandardMessageEvent): Boolean = dao.enqueue(event.toEntity()) != -1L
    suspend fun next()=dao.nextEvents().map { it.toEvent() }
    suspend fun update(event: StandardMessageEvent,status:EventQueueStatus,result:String?,duration:Long?,retry:Int=event.retryCount,contact:String?=event.localContactId,conversation:String?=event.localConversationId)=dao.update(event.eventId,status.name,result,duration,retry,contact,conversation)
}
class ContactResolver(private val service: ConversationService) { suspend fun resolve(event: StandardMessageEvent): Contact = service.createOrOpen(event.platform,event.platformUserIdentifier,event.displayName,event.platformConversationIdentifier).first }
class ConversationResolver(private val service: ConversationService) { suspend fun resolve(event: StandardMessageEvent): Conversation = service.createOrOpen(event.platform,event.platformUserIdentifier,event.displayName,event.platformConversationIdentifier).second }
class EventDispatcher(private val queue:PlatformEventQueue, private val contactResolver:ContactResolver, private val conversationResolver:ConversationResolver, private val conversationService:ConversationService, private val drafts:DraftGenerationService) {
    private val mutex=Mutex()
    suspend fun drain()=mutex.withLock { queue.next().forEach { source -> val start=System.nanoTime(); try { queue.update(source,EventQueueStatus.PROCESSING,"Processing",null); val contact=contactResolver.resolve(source); val conversation=conversationResolver.resolve(source); val resolved=source.copy(localContactId=contact.id,localConversationId=conversation.id); conversationService.recordTurn(contact.id,conversation.id,source.direction,source.messageContent); drafts.generate(source.platform, contact.id, conversation.id, source.messageContent); queue.update(resolved,EventQueueStatus.PROCESSED,"Routed to Conversation Engine",(System.nanoTime()-start)/1_000_000) } catch(e:Exception){ queue.update(source,EventQueueStatus.FAILED,e.javaClass.simpleName,(System.nanoTime()-start)/1_000_000,source.retryCount + 1) } } }
}
class NotificationProcessingPipeline(private val validator:EventValidator,private val manager:PlatformManager,private val queue:PlatformEventQueue,private val dispatcher:EventDispatcher) {
    suspend fun process(sbn:StatusBarNotification): EventQueueStatus { validator.validate(sbn)?.let{return EventQueueStatus.REJECTED}; val adapter=manager.adapter(sbn.packageName)?:return EventQueueStatus.REJECTED; val parsed=adapter.parse(sbn).getOrElse{return EventQueueStatus.REJECTED}; val metadata=NotificationMetadata(sbn.packageName,sbn.key,sbn.id,sbn.tag,sbn.notification.channelId,false,false,sbn.postTime); val event=StandardMessageEvent(UUID.randomUUID().toString(),parsed.platform,parsed.platformUserIdentifier,parsed.platformConversationIdentifier,displayName=parsed.displayName,messageContent=parsed.messageContent,timestampEpochMs=parsed.timestampEpochMs,direction=parsed.direction,metadata=metadata,fingerprint=fingerprint(parsed,metadata)); if(!queue.enqueue(event))return EventQueueStatus.DUPLICATE; dispatcher.drain(); return EventQueueStatus.PROCESSED }
    private fun fingerprint(parsed:ParsedPlatformNotification,metadata:NotificationMetadata):String { val raw="${parsed.platform}|${parsed.platformUserIdentifier}|${parsed.platformConversationIdentifier}|${parsed.messageContent}|${parsed.timestampEpochMs/1000}|${metadata.notificationId}|${metadata.notificationKey}"; return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString(""){"%02x".format(it)} }
}
private fun StandardMessageEvent.toEntity()=PlatformEventEntity(eventId,platform.name,platformUserIdentifier,platformConversationIdentifier,localContactId,localConversationId,displayName,messageContent,timestampEpochMs,direction.name,metadata.packageName,metadata.notificationKey,metadata.notificationId,metadata.notificationTag,metadata.channelId,metadata.isGroupSummary,metadata.isSilent,fingerprint,retryCount)
private fun PlatformEventEntity.toEvent()=StandardMessageEvent(eventId,MessagingPlatform.valueOf(platform),platformUserIdentifier,platformConversationIdentifier,localContactId,localConversationId,displayName,messageContent,timestampEpochMs,MessageDirection.valueOf(direction),NotificationMetadata(packageName,notificationKey,notificationId,notificationTag,channelId,isGroupSummary,isSilent,timestampEpochMs),fingerprint,retryCount)
