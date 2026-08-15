package com.replymate.data.db;

import java.util.Arrays;
import java.util.List;

/** Schema v7 (P-intelligence-15): the contact table gains GROUP persistence.
 *  The MessagingStyle isGroupConversation flag was parsed at capture (schema v6
 *  era) but dropped before storage, so "this conversation is a group" had to be
 *  guessed downstream. is_group stores the capture-time fact: 1 = the source
 *  app itself declared a group conversation. Existing contacts default 0 —
 *  groups flip to 1 the next time one of their messages is ingested. */
public final class SchemaV7 {

    private SchemaV7() { }

    public static final List<String> DDL = Arrays.asList(
        "ALTER TABLE contact ADD COLUMN is_group INTEGER NOT NULL DEFAULT 0"
    );
}
