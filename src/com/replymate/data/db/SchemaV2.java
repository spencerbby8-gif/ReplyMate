package com.replymate.data.db;

import java.util.Arrays;
import java.util.List;

/** Schema v2 (P3): draft.favorite flag + widen contact_channel.channel CHECK to the
 *  P3 provider set (signal/gmessages/messenger/slack/discord/instagram/x/tiktok).
 *  SQLite cannot ALTER a CHECK constraint → rebuild contact_channel preserving rows. */
public final class SchemaV2 {

    private SchemaV2() { }

    public static final List<String> DDL = Arrays.asList(
        "ALTER TABLE draft ADD COLUMN favorite INTEGER NOT NULL DEFAULT 0",

        "CREATE TABLE contact_channel_v2("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " contact_id INTEGER NOT NULL REFERENCES contact(id) ON DELETE CASCADE,"
            + " channel TEXT NOT NULL CHECK(channel IN("
            + "'whatsapp','telegram','manual','signal','gmessages','messenger',"
            + "'slack','discord','instagram','x','tiktok')),"
            + " remote_key TEXT NOT NULL,"
            + " last_seen_at INTEGER NOT NULL DEFAULT 0,"
            + " UNIQUE(channel, remote_key))",

        "INSERT INTO contact_channel_v2(id, contact_id, channel, remote_key, last_seen_at)"
            + " SELECT id, contact_id, channel, remote_key, last_seen_at FROM contact_channel",

        "DROP TABLE contact_channel",

        "ALTER TABLE contact_channel_v2 RENAME TO contact_channel"
    );
}
