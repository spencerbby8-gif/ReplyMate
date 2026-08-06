package com.replymate.data.db;

import java.util.Arrays;
import java.util.List;

/** Schema v3 (P4 customization + learning): style_setting (global user voice +
 *  per-contact overrides incl. the custom prompt box) and style_signal (learning
 *  signals from approved/edited/regenerated/rejected replies).
 *  Global rows carry contact_id NULL; SQLite treats NULLs as distinct in plain
 *  UNIQUE constraints, so uniqueness is enforced with two partial indexes instead. */
public final class SchemaV3 {

    private SchemaV3() { }

    public static final List<String> DDL = Arrays.asList(
        "CREATE TABLE style_setting("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " contact_id INTEGER NULL REFERENCES contact(id) ON DELETE CASCADE,"
            + " key TEXT NOT NULL,"
            + " value TEXT NOT NULL,"
            + " updated_at INTEGER NOT NULL)",

        "CREATE UNIQUE INDEX uq_setting_global"
            + " ON style_setting(key) WHERE contact_id IS NULL",

        "CREATE UNIQUE INDEX uq_setting_contact"
            + " ON style_setting(contact_id, key) WHERE contact_id IS NOT NULL",

        "CREATE TABLE style_signal("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " contact_id INTEGER NOT NULL REFERENCES contact(id) ON DELETE CASCADE,"
            + " kind TEXT NOT NULL CHECK(kind IN('approved','edited','regenerated','rejected')),"
            + " detail TEXT NOT NULL DEFAULT '',"
            + " draft_id INTEGER NULL REFERENCES draft(id) ON DELETE SET NULL,"
            + " created_at INTEGER NOT NULL)",

        "CREATE INDEX idx_signal_contact_ts ON style_signal(contact_id, created_at)"
    );
}
