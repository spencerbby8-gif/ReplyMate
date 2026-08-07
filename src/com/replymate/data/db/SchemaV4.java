package com.replymate.data.db;

import java.util.Arrays;
import java.util.List;

/** Schema v4 (P-polish provider abstraction): provider_def is rebuilt so the
 *  type CHECK('gemini') and the hardcoded model_name DEFAULT 'gemini-2.5-flash' go away —
 *  any provider type is storable and NO model name is ever baked into storage
 *  (models come from live discovery / user choice). Existing config rows
 *  (label/base_url/model/key_ref/is_active) are preserved verbatim, so the
 *  Keystore vault alias keeps matching and no key is lost. */
public final class SchemaV4 {

    private SchemaV4() { }

    public static final List<String> DDL = Arrays.asList(
        "CREATE TABLE provider_def_v4("
            + " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            + " type TEXT NOT NULL,"
            + " label TEXT NOT NULL DEFAULT '',"
            + " base_url TEXT NOT NULL DEFAULT '',"
            + " model_name TEXT NOT NULL DEFAULT '',"
            + " key_ref TEXT NOT NULL,"
            + " is_active INTEGER NOT NULL DEFAULT 0,"
            + " created_at INTEGER NOT NULL)",

        "INSERT INTO provider_def_v4(id, type, label, base_url, model_name, key_ref,"
            + " is_active, created_at)"
            + " SELECT id, type, label, base_url, model_name, key_ref, is_active,"
            + " created_at FROM provider_def",

        "DROP TABLE provider_def",

        "ALTER TABLE provider_def_v4 RENAME TO provider_def"
    );
}
