package com.replymate.data.db;

import java.util.ArrayList;
import java.util.List;

/** Versioned, append-only migration registry (BLUEPRINT §3.3).
 *  Runner applies pending steps in order and stamps user_version after each.
 *  Runs identically on device (DbHelper) and in tests (JDBC). */
public final class Migrations {

    public static final int LATEST = 1;

    public interface Migration {
        int version();
        List<String> statements();
    }

    private Migrations() { }

    public static List<Migration> all() {
        List<Migration> list = new ArrayList<Migration>();
        list.add(new Migration() {
            @Override public int version() { return 1; }
            @Override public List<String> statements() { return SchemaV1.DDL; }
        });
        // V2, V3 … append here. Never edit earlier migrations.
        return list;
    }

    /** Apply every migration newer than the database's current user_version. Idempotent. */
    public static void migrate(ExecSql db) {
        int current = db.getUserVersion();
        for (Migration m : all()) {
            if (m.version() > current) {
                for (String sql : m.statements()) db.exec(sql);
                db.setUserVersion(m.version());
            }
        }
    }
}
