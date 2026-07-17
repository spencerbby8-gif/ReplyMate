package com.replymate.core.platform

import com.replymate.core.conversation.MessageDirection
import com.replymate.core.conversation.MessagingPlatform

enum class EventQueueStatus { QUEUED, PROCESSING, PROCESSED, FAILED, DUPLICATE, REJECTED }
enum class EventRejection { UNSUPPORTED_PACKAGE, SILENT_NOTIFICATION, GROUP_SUMMARY, MISSING_MESSAGE, MISSING_STABLE_IDENTIFIER, UNSUPPORTED_FORMAT, DUPLICATE }
data class NotificationMetadata(val packageName: String, val notificationKey: String, val notificationId: Int, val notificationTag: String?, val channelId: String?, val isGroupSummary: Boolean, val isSilent: Boolean, val postedAtEpochMs: Long)
data class StandardMessageEvent(val eventId: String, val platform: MessagingPlatform, val platformUserIdentifier: String, val platformConversationIdentifier: String, val localContactId: String? = null, val localConversationId: String? = null, val displayName: String, val messageContent: String, val timestampEpochMs: Long, val direction: MessageDirection, val metadata: NotificationMetadata, val fingerprint: String, val retryCount: Int = 0)
data class ParsedPlatformNotification(val platform: MessagingPlatform, val platformUserIdentifier: String, val platformConversationIdentifier: String, val displayName: String, val messageContent: String, val timestampEpochMs: Long, val direction: MessageDirection)
data class PlatformEventDiagnostic(val eventId: String, val platform: MessagingPlatform?, val status: EventQueueStatus, val result: String?, val durationMs: Long?, val duplicate: Boolean, val metadata: NotificationMetadata?)
