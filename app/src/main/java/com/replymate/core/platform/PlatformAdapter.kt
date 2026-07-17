package com.replymate.core.platform

import android.service.notification.StatusBarNotification
import com.replymate.core.conversation.MessagingPlatform

interface PlatformAdapter { val platform: MessagingPlatform; val packageName: String; fun parse(sbn: StatusBarNotification): Result<ParsedPlatformNotification> }
abstract class MessagingStyleAdapter(final override val platform: MessagingPlatform, final override val packageName: String) : PlatformAdapter {
    override fun parse(sbn: StatusBarNotification): Result<ParsedPlatformNotification> = runCatching {
        val notification = sbn.notification
        val messages = notification.extras.getParcelableArray("android.messages") ?: error("No messaging-style messages")
        val bundle = messages.lastOrNull() as? android.os.Bundle ?: error("Unsupported message bundle")
        val text = bundle.getCharSequence("text")?.toString()?.trim().orEmpty().ifBlank { error("Empty message") }
        val person = bundle.getBundle("sender_person")?.let { android.app.Person.fromBundle(it) }
        val userId = person?.key?.takeIf { it.isNotBlank() } ?: error("Missing stable sender identifier")
        val conversationId = notification.extras.getString("android.conversationShortcutId")?.takeIf { it.isNotBlank() } ?: userId
        ParsedPlatformNotification(platform, userId, conversationId, person.name?.toString().orEmpty(), text, bundle.getLong("time", sbn.postTime), com.replymate.core.conversation.MessageDirection.INCOMING)
    }
}
class TelegramPlatformAdapter : MessagingStyleAdapter(MessagingPlatform.TELEGRAM, "org.telegram.messenger")
class WhatsAppPlatformAdapter : MessagingStyleAdapter(MessagingPlatform.WHATSAPP, "com.whatsapp")
