package com.replymate.core.listener;

import com.replymate.core.model.Channel;

/** Platform-neutral view of ONE parsed notification message (extracted by the app layer
 *  from Notification.MessagingStyle). All listener logic operates on this type. */
public final class NotifEvent {
    public Channel channel;
    public String packageName;       // diagnostic only
    public String conversationTitle; // group title or partner name (nullable)
    public String senderName;        // sender of THIS message (nullable)
    public String ownerName;         // device owner's display name in that chat (nullable)
    public String text;              // nullable/empty for media-only
    public long timestampMs;
    public boolean group;
    public boolean hasAttachment;

    public NotifEvent() { }
}
