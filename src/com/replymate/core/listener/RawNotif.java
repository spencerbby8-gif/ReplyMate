package com.replymate.core.listener;

import java.util.ArrayList;
import java.util.List;

/** Platform-neutral copy of ONE posted notification, produced by the app layer
 *  (NotifExtractor) as a dumb read of framework extras. ALL parsing decisions
 *  happen in core parsers over this type (JVM-testable; BLUEPRINT §1 layering). */
public final class RawNotif {

    /** One MessagingStyle message entry, when the posting app provides history. */
    public static final class Entry {
        public String text;            // null/empty for media-only
        public long timestampMs;
        public String senderName;      // resolved display name when present
        public boolean hasAttachment;
    }

    public String packageName;
    public String title;               // EXTRA_TITLE (nullable)
    public String text;                // EXTRA_TEXT (nullable)
    public String bigText;             // EXTRA_BIG_TEXT (nullable)
    public String category;            // Notification.category e.g. "msg","social","promo" (nullable)
    public String convTitle;           // android.conversationTitle (nullable)
    public String ownerName;           // messagingUser/selfDisplayName (nullable)
    public Boolean group;              // isGroupConversation tri-state: null = not provided
    public long postTimeMs;
    public final List<Entry> messages = new ArrayList<Entry>();   // android.messages contents

    public RawNotif() { }
}
