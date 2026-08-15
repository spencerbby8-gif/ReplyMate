package com.replymate.core.listener;

/** P-intelligence-17: WHAT an incoming notification item IS, classified BEFORE any
 *  generation decision. Persisted on the stored message (schema v9, item_class)
 *  so the delivery/audit chain can report classification → reason → source →
 *  target for every item. UNKNOWN always fails closed — it never becomes a
 *  normal message. */
public enum ItemClass {
    REAL_1TO1("real_1to1"),
    GROUP_MESSAGE("group_message"),
    DIRECT_REPLY("direct_reply"),
    MENTION("mention"),
    ANNOUNCEMENT("announcement"),
    BROADCAST("broadcast"),
    SERVICE("service"),
    SYSTEM("system"),
    REACTION("reaction"),
    CALL("call"),
    MEDIA_ONLY("media_only"),
    UNKNOWN("unknown");

    public final String wire;
    ItemClass(String wire) { this.wire = wire; }

    /** Classes that must stop BEFORE generation with the mandated explanation:
     *  "This message can't be replied to from ReplyMate." (schema v9 rows) */
    public boolean isNonReplyable() {
        return this == ANNOUNCEMENT || this == BROADCAST
            || this == SERVICE || this == SYSTEM;
    }
}
