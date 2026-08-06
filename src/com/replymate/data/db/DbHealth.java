package com.replymate.data.db;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

/** Runtime database self-test (P0 hotfix task: on-device init test path).
 *
 *  Exercises the REAL open path — configuration pragmas, schema version, tables,
 *  foreign-key flag, journal mode, and an actual write probe — then reports a
 *  structured result the UI can present safely, instead of a bare crash.
 *
 *  Note the rawQuery helper is used for every PRAGMA read/apply: assignment and
 *  query pragmas can return rows, which execSQL rejects on Android. */
public final class DbHealth {

    private static boolean runtimeTuningApplied;

    public static final class Report {
        public boolean ok;
        public boolean opened;
        public boolean writable;
        public boolean foreignKeysOn;
        public String journalMode = "?";
        public int version = -1;
        public int tables = -1;
        public String error;

        public String oneLine() {
            return ok
                ? ("schema v" + version + " · " + tables + " tables · " + journalMode)
                : shortReason();
        }

        public String shortReason() {
            return error == null ? "unexpected schema state" : error;
        }

        public String details() {
            StringBuilder sb = new StringBuilder();
            sb.append("opened: ").append(opened).append('\n');
            sb.append("schema version: ").append(version).append(" (expected ").append(Migrations.LATEST).append(")\n");
            sb.append("tables: ").append(tables).append('\n');
            sb.append("foreign_keys: ").append(foreignKeysOn ? "ON" : "OFF").append('\n');
            sb.append("journal_mode: ").append(journalMode).append('\n');
            sb.append("write probe: ").append(writable ? "passed" : "FAILED").append('\n');
            if (error != null) sb.append("error: ").append(error);
            return sb.toString();
        }
    }

    private DbHealth() { }

    /** Full health check. Safe to call from the UI thread (fast, no network). */
    public static Report check(DbHelper helper) {
        Report r = new Report();
        try {
            SQLiteDatabase db = helper.getReadableDatabase();
            r.opened = true;
            r.version = db.getVersion();
            r.tables = countTables(db);
            r.foreignKeysOn = pragmaEqualsOne(db, "PRAGMA foreign_keys");
            r.journalMode = pragmaText(db, "PRAGMA journal_mode");
            applyRuntimeTuningOnce(db);
            r.writable = writeProbe(helper);

            r.ok = r.opened
                && r.version == Migrations.LATEST
                && r.tables >= 10
                && r.writable;
            if (!r.ok && r.error == null) {
                r.error = "schema v" + r.version + " with " + r.tables + " tables (expected v"
                    + Migrations.LATEST + " with ≥10)";
            }
        } catch (RuntimeException e) {
            r.ok = false;
            r.error = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        return r;
    }

    /** synchronous=NORMAL: post-open tuning, via rawQuery (row-safe), failures ignored. */
    private static void applyRuntimeTuningOnce(SQLiteDatabase db) {
        if (runtimeTuningApplied) return;
        runtimeTuningApplied = true;
        ignoreClose(pragmaRaw(db, "PRAGMA synchronous=NORMAL"));
    }

    private static int countTables(SQLiteDatabase db) {
        Cursor c = db.rawQuery(
            "SELECT COUNT(*) FROM sqlite_master WHERE type='table'"
                + " AND name NOT LIKE 'sqlite_%' AND name NOT LIKE 'android_%'", null);
        try {
            return c.moveToFirst() ? c.getInt(0) : -1;
        } finally {
            c.close();
        }
    }

    private static boolean pragmaEqualsOne(SQLiteDatabase db, String pragma) {
        return "1".equals(pragmaText(db, pragma));
    }

    /** Row-safe PRAGMA reader — returns first column of first row, or "" on any issue. */
    private static String pragmaText(SQLiteDatabase db, String pragma) {
        Cursor c = null;
        try {
            c = db.rawQuery(pragma, null);
            if (c != null && c.moveToFirst() && c.getColumnCount() > 0 && !c.isNull(0)) {
                return String.valueOf(c.getString(0));
            }
        } catch (RuntimeException ignored) {
            // Pragma inspection must never break DB initialization.
        } finally {
            ignoreClose(c);
        }
        return "";
    }

    private static Cursor pragmaRaw(SQLiteDatabase db, String pragma) {
        try {
            return db.rawQuery(pragma, null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** Proves real write capability: insert + delete inside a transaction in app_kv. */
    private static boolean writeProbe(DbHelper helper) {
        try {
            SQLiteDatabase db = helper.getWritableDatabase();
            db.beginTransaction();
            try {
                db.execSQL("INSERT OR REPLACE INTO app_kv(key,value) VALUES('__selftest__','1')");
                db.execSQL("DELETE FROM app_kv WHERE key='__selftest__'");
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void ignoreClose(Cursor c) {
        if (c != null) {
            try { c.close(); } catch (RuntimeException ignored) { }
        }
    }
}
