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
        public String senderKey;       // Person key (native per-app user id), nullable
        public String senderUri;       // Person uri (e.g. tel:/username), nullable
        public boolean hasAttachment;  // "uri"/"type" present in the bundle
        public String mimeType;        // attachment MIME when the app exposed it
        public String dataUri;         // attachment content reference (local only)
    }

    public String packageName;
    public String title;               // EXTRA_TITLE (nullable)
    public String text;                // EXTRA_TEXT (nullable)
    public String bigText;             // EXTRA_BIG_TEXT (nullable)
    public String category;            // Notification.category e.g. "msg","social","promo" (nullable)
    public String convTitle;           // android.conversationTitle (nullable)
    public String conversationId;      // notification shortcut id: the SOURCE APP's native
                                       // conversation identifier (WhatsApp JID thread,
                                       // Slack/Discord channel id, …) — API 29+, nullable
    public String ownerName;           // messagingUser/selfDisplayName (nullable)
    public String ownerKey;            // messagingUser Person key (nullable)
    public Boolean group;              // isGroupConversation tri-state: null = not provided
    public long postTimeMs;
    public boolean ongoing;            // ongoing-event/foreground-service flags (P-ux-fix status gate)
    public int progressMax;            // EXTRA_PROGRESS_MAX (>0 ⇒ progress housekeeping)
    public final List<Entry> messages = new ArrayList<Entry>();   // android.messages contents

    public RawNotif() { }
}
