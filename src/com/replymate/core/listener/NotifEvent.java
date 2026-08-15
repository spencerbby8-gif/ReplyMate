package com.replymate.core.listener;

import com.replymate.core.model.Channel;

/** Platform-neutral view of ONE parsed notification message (extracted by the app layer
 *  from Notification.MessagingStyle). All listener logic operates on this type. */
public final class NotifEvent {
    public Channel channel;
    public String packageName;       // diagnostic only
    public String conversationTitle; // group title or partner name (nullable)
    public String conversationId;    // source app's native conversation id (nullable)
    public String senderName;        // sender of THIS message (nullable)
    public String senderKey;         // sender Person key (native per-app user id), nullable
    public String senderUri;         // sender Person uri, nullable
    public String ownerName;         // device owner's display name in that chat (nullable)
    public String ownerKey;          // owner Person key (direction disambiguation), nullable
    public String text;              // nullable/empty for media-only
    public long timestampMs;
    public boolean group;
    /** P-intelligence-15: came from MessagingStyle's HISTORIC list — older context
     *  the app volunteered for grounding. Stored (deduped) but NEVER allowed to
     *  ping a burst/draft. */
    public boolean historic;
    public boolean hasAttachment;
    /** P-intelligence-17: this item's OWN notification exposes a free-form quick-
     *  reply action (RemoteInput allowFreeFormInput on standard OR wearable
     *  surface). Feeds the DIRECT_REPLY item class — capability evidence belongs
     *  to this item, never borrowed from another notification. */
    public boolean hasFreeFormReply;
    /** WHAT the item is (null = not classified by the parser; the ingest pipeline
     *  re-derives it from evidence). Separate from source identity by design. */
    public com.replymate.core.model.ContentKind contentKind;
    public String mediaMime;         // attachment MIME when exposed (null otherwise)
    public String mediaUri;          // attachment content reference (local only, null) 

    public NotifEvent() { }
}
