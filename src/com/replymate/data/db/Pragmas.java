package com.replymate.data.db;

import android.database.sqlite.SQLiteDatabase;

/** Connection pragmas applied in SQLiteOpenHelper.onConfigure (BLUEPRINT §3.1).
 *
 *  ── P0 HOTFIX 0.0.2 — root cause documented ────────────────────────────────
 *  v0.0.1 executed  db.execSQL("PRAGMA journal_mode=WAL")  here.
 *  journal_mode is a ROW-RETURNING pragma; Android's execSQL → nativeExecute
 *  throws SQLiteException for any statement that yields rows ("Queries can be
 *  performed using SQLiteDatabase query or rawQuery methods only."). The
 *  exception was thrown from onConfigure, the first database open aborted,
 *  and HomeActivity fell back to "Database error - see logs".
 *  JVM tests never executed this platform class — visible in device runtime only.
 *  ──────────────────────────────────────────────────────────────────────────
 *  Fix: both settings use official framework APIs (no SQL executed, no rows
 *  returned). The remaining tuning pragma (synchronous=NORMAL) is unnecessary
 *  during configuration and is applied AFTER a successful open by DbHealth via
 *  a rawQuery-based, best-effort path.
 */
public final class Pragmas {

    private Pragmas() { }

    public static void apply(SQLiteDatabase db) {
        // WAL — documented for use in onConfigure; no SQL, no row-return hazard.
        db.enableWriteAheadLogging();
        // Foreign key enforcement per connection (we also rely on FK CASCADE).
        db.setForeignKeyConstraintsEnabled(true);
    }
}
