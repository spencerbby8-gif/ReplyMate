package com.replymate.data.db;

import java.util.Arrays;
import java.util.List;

/** Schema v9 (P-intelligence-17): the message table gains the two things the
 *  safe-delivery audit needs from every stored row:
 *   - item_class: WHAT the notification was (ItemClassifier — real_1to1,
 *     group_message, mention, announcement, service, system, reaction, call,
 *     media_only, unknown), stamped at ingest before any generation decision;
 *   - conv_id / conv_title: the CONVERSATION IDENTITY of the message itself
 *     (platform shortcut id / conversationTitle). The DeliveryGuard compares the
 *     captured reply target against the message's own identity — a target from a
 *     different conversation can never fire for this message (the Discord
 *     cross-channel borrow).
 *  Pre-v9 rows default '' — honestly "unverifiable at conversation level"; the
 *  guard says so in the ledger instead of pretending verification. */
public final class SchemaV9 {

    private SchemaV9() { }

    public static final List<String> DDL = Arrays.asList(
        "ALTER TABLE message ADD COLUMN item_class TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE message ADD COLUMN conv_id TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE message ADD COLUMN conv_title TEXT NOT NULL DEFAULT ''"
    );
}
