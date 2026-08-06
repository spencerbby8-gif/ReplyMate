package com.replymate.data.dao;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.replymate.data.db.DbHelper;

/** Row-level access to app_kv. Secrets must never pass through here (SecretVault's job). */
public final class KvDao {
    private final DbHelper helper;

    public KvDao(DbHelper helper) {
        this.helper = helper;
    }

    public void put(String key, String value) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("value", value);
        db.insertWithOnConflict("app_kv", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    public String get(String key, String defValue) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query("app_kv", new String[] {"value"}, "key=?",
            new String[] {key}, null, null, null);
        try {
            if (c.moveToFirst()) return c.getString(0);
            return defValue;
        } finally {
            c.close();
        }
    }

    public boolean contains(String key) {
        SQLiteDatabase db = helper.getReadableDatabase();
        Cursor c = db.query("app_kv", new String[] {"key"}, "key=?",
            new String[] {key}, null, null, null);
        try {
            return c.moveToFirst();
        } finally {
            c.close();
        }
    }

    public void delete(String key) {
        helper.getWritableDatabase().delete("app_kv", "key=?", new String[] {key});
    }
}
