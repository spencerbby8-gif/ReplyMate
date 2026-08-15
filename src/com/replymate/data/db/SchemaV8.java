package com.replymate.data.db;

import java.util.Arrays;
import java.util.List;

/** Schema v8 (P-intelligence-16b): the message table gains the platform's STABLE
 *  SENDER ID (MessagingStyle Person key → sender_key). schema v6 persisted the
 *  sender's DISPLAY name; display names change and collide — the participant
 *  registry (group conversation engine) keys members by this stable id instead,
 *  so two members who share a name (or one member who renames) are never
 *  confused. Historical rows default '' (unknown — honestly never back-filled
 *  from guesses); the name-only path keeps working as the registry's fallback. */
public final class SchemaV8 {

    private SchemaV8() { }

    public static final List<String> DDL = Arrays.asList(
        "ALTER TABLE message ADD COLUMN sender_key TEXT NOT NULL DEFAULT ''"
    );
}
