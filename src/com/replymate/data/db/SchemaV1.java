package com.replymate.data.db;

import java.util.Arrays;
import java.util.List;

/** Schema v1 — authoritative DDL from BLUEPRINT §3.2 (creation order arranged so that
 *  referenced tables exist first). Pure constants: no platform imports by design. */
public final class SchemaV1 {

    private SchemaV1() { }

    public static final List<String> DDL = Arrays.asList(
        "CREATE TABLE style_profile("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " scope TEXT NOT NULL CHECK(scope IN('global','contact')),"
            + " contact_id INTEGER NULL REFERENCES contact(id) ON DELETE CASCADE,"
            + " sample_messages TEXT NOT NULL DEFAULT '',"
            + " derived_rules TEXT NOT NULL DEFAULT '',"
            + " updated_at INTEGER NOT NULL)",

        "CREATE TABLE contact("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " display_name TEXT NOT NULL,"
            + " relationship_type TEXT NOT NULL DEFAULT '',"
            + " relationship_notes TEXT NOT NULL DEFAULT '',"
            + " tone_override TEXT NOT NULL DEFAULT '',"
            + " language_pref TEXT NOT NULL DEFAULT '',"
            + " style_profile_id INTEGER NULL REFERENCES style_profile(id) ON DELETE SET NULL,"
            + " ai_enabled INTEGER NOT NULL DEFAULT 1,"
            + " memory_enabled INTEGER NOT NULL DEFAULT 1,"
            + " private_mode INTEGER NOT NULL DEFAULT 0,"
            + " created_at INTEGER NOT NULL,"
            + " updated_at INTEGER NOT NULL)",

        "CREATE TABLE contact_channel("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " contact_id INTEGER NOT NULL REFERENCES contact(id) ON DELETE CASCADE,"
            + " channel TEXT NOT NULL CHECK(channel IN('whatsapp','telegram','manual')),"
            + " remote_key TEXT NOT NULL,"
            + " last_seen_at INTEGER NOT NULL DEFAULT 0,"
            + " UNIQUE(channel, remote_key))",

        "CREATE TABLE message("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " contact_id INTEGER NOT NULL REFERENCES contact(id) ON DELETE CASCADE,"
            + " channel TEXT NOT NULL,"
            + " direction TEXT NOT NULL CHECK(direction IN('in','out')),"
            + " body TEXT NOT NULL,"
            + " sent_at INTEGER NOT NULL,"
            + " notif_key TEXT NULL,"
            + " source TEXT NOT NULL CHECK(source IN('listener','manual','import')),"
            + " UNIQUE(channel, notif_key))",

        "CREATE INDEX idx_message_contact_ts ON message(contact_id, sent_at)",

        "CREATE TABLE memory_fact("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " contact_id INTEGER NOT NULL REFERENCES contact(id) ON DELETE CASCADE,"
            + " category TEXT NOT NULL CHECK(category IN('person','preference','event','relation','comm_style','boundary')),"
            + " text TEXT NOT NULL,"
            + " text_norm TEXT NOT NULL,"
            + " importance INTEGER NOT NULL DEFAULT 3,"
            + " confidence REAL NOT NULL DEFAULT 0.7,"
            + " pinned INTEGER NOT NULL DEFAULT 0,"
            + " disabled INTEGER NOT NULL DEFAULT 0,"
            + " source_message_id INTEGER NULL REFERENCES message(id) ON DELETE SET NULL,"
            + " created_at INTEGER NOT NULL,"
            + " updated_at INTEGER NOT NULL,"
            + " UNIQUE(contact_id, text_norm))",

        "CREATE INDEX idx_fact_contact_active ON memory_fact(contact_id, disabled)",

        "CREATE TABLE contact_summary("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " contact_id INTEGER NOT NULL REFERENCES contact(id) ON DELETE CASCADE,"
            + " summary_text TEXT NOT NULL,"
            + " covers_until_ts INTEGER NOT NULL,"
            + " version INTEGER NOT NULL,"
            + " created_at INTEGER NOT NULL,"
            + " UNIQUE(contact_id, version))",

        "CREATE TABLE provider_def("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " type TEXT NOT NULL CHECK(type IN('gemini')),"
            + " label TEXT NOT NULL DEFAULT 'Gemini',"
            + " base_url TEXT NOT NULL DEFAULT 'https://generativelanguage.googleapis.com',"
            + " model_name TEXT NOT NULL DEFAULT 'gemini-2.5-flash',"
            + " key_ref TEXT NOT NULL,"
            + " is_active INTEGER NOT NULL DEFAULT 0,"
            + " created_at INTEGER NOT NULL)",

        "CREATE TABLE draft("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " contact_id INTEGER NOT NULL REFERENCES contact(id) ON DELETE CASCADE,"
            + " in_reply_to_id INTEGER NULL REFERENCES message(id) ON DELETE SET NULL,"
            + " prompt_snapshot TEXT NOT NULL,"
            + " reply_text TEXT NOT NULL,"
            + " model TEXT NOT NULL,"
            + " variant_group TEXT NOT NULL,"
            + " status TEXT NOT NULL CHECK(status IN('generated','edited','copied','sent')) DEFAULT 'generated',"
            + " latency_ms INTEGER NOT NULL DEFAULT 0,"
            + " tokens_in INTEGER NOT NULL DEFAULT 0,"
            + " tokens_out INTEGER NOT NULL DEFAULT 0,"
            + " created_at INTEGER NOT NULL)",

        "CREATE INDEX idx_draft_contact_ts ON draft(contact_id, created_at)",

        "CREATE TABLE usage_event("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " ts INTEGER NOT NULL,"
            + " model TEXT NOT NULL,"
            + " tokens_in INTEGER NOT NULL,"
            + " tokens_out INTEGER NOT NULL,"
            + " kind TEXT NOT NULL CHECK(kind IN('reply','summary','extract','style')))",

        "CREATE INDEX idx_usage_ts ON usage_event(ts)",

        "CREATE TABLE app_kv("
            + " key TEXT PRIMARY KEY,"
            + " value TEXT NOT NULL)"
    );
}
