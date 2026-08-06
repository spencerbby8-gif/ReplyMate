package com.replymate.data.db;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

/** Owns the replymate.db file (app-private storage — v1 security model, decision #2).
 *  Runs migrations through the platform-neutral Migrations runner.
 *  Runtime health checks live in DbHealth (post-0.0.2); this class stays minimal. */
public final class DbHelper extends SQLiteOpenHelper {

    public static final String DB_NAME = "replymate.db";

    public DbHelper(Context context) {
        super(context, DB_NAME, null, Migrations.LATEST);
    }

    @Override public void onConfigure(SQLiteDatabase db) {
        super.onConfigure(db);
        Pragmas.apply(db);          // WAL + foreign keys via framework APIs (0.0.2 hotfix)
    }

    @Override public void onCreate(SQLiteDatabase db) {
        Migrations.migrate(adapter(db));
    }

    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        Migrations.migrate(adapter(db));
    }

    @Override public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // Rollback policy (P0): refuse silently-destructive downgrades.
        throw new android.database.sqlite.SQLiteException(
            "cannot downgrade replymate.db from v" + oldVersion + " to v" + newVersion);
    }

    /** Bridge between the framework database and the platform-neutral runner. */
    public static ExecSql adapter(final SQLiteDatabase db) {
        return new ExecSql() {
            @Override public void exec(String sql) { db.execSQL(sql); }
            @Override public int getUserVersion() { return db.getVersion(); }
            @Override public void setUserVersion(int version) { db.setVersion(version); }
        };
    }
}
