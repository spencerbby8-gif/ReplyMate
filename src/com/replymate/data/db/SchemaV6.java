package com.replymate.data.db;

import java.util.Arrays;
import java.util.List;

/** Schema v6 (P-memory-audit): the message table gains per-message SENDER
 *  attribution. The listener has always parsed the sender of each MessagingStyle
 *  entry (NotifEvent.senderName) but it was dropped at ingest — fine for 1:1 chats
 *  (contact name == sender), WRONG for group chats, where every member's message
 *  would be attributed to the conversation title. sender_name stores the actual
 *  sender for incoming listener rows ("" = unknown / outgoing / manual). */
public final class SchemaV6 {

    private SchemaV6() { }

    public static final List<String> DDL = Arrays.asList(
        "ALTER TABLE message ADD COLUMN sender_name TEXT NOT NULL DEFAULT ''"
    );
}
