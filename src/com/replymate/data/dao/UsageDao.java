package com.replymate.data.dao;

import android.content.ContentValues;
import android.database.Cursor;
import com.replymate.core.model.UsageEvent;
import com.replymate.core.model.UsageKind;
import com.replymate.data.db.DbHelper;

/** Row access to usage_event (token/cost metering). */
public final class UsageDao {
    private final DbHelper helper;

    public UsageDao(DbHelper helper) { this.helper = helper; }

    public long insert(UsageEvent e) {
        ContentValues v = new ContentValues();
        v.put("ts", e.ts);
        v.put("model", e.model);
        v.put("tokens_in", e.tokensIn);
        v.put("tokens_out", e.tokensOut);
        v.put("kind", e.kind.wire);
        return helper.getWritableDatabase().insert("usage_event", null, v);
    }

    public long totalTokensSince(long ts) {
        Cursor c = helper.getReadableDatabase().rawQuery(
            "SELECT COALESCE(SUM(tokens_in + tokens_out), 0) FROM usage_event WHERE ts>=?",
            new String[] {String.valueOf(ts)});
        try {
            return c.moveToFirst() ? c.getLong(0) : 0;
        } finally {
            c.close();
        }
    }

    public java.util.List<com.replymate.core.model.UsageEvent> since(long ts) {
        android.database.Cursor c = helper.getReadableDatabase().query(
            "usage_event", null, "ts>=?", new String[] {String.valueOf(ts)},
            null, null, "ts DESC, id DESC");
        java.util.List<com.replymate.core.model.UsageEvent> out =
            new java.util.ArrayList<com.replymate.core.model.UsageEvent>();
        try {
            while (c.moveToNext()) out.add(event(c));
        } finally {
            c.close();
        }
        return out;
    }

    public int countSince(long ts) {
        Cursor c = helper.getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM usage_event WHERE ts>=?",
            new String[] {String.valueOf(ts)});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public void purgeBefore(long ts) {
        helper.getWritableDatabase().delete("usage_event", "ts<?",
            new String[] {String.valueOf(ts)});
    }

    private static com.replymate.core.model.UsageEvent event(android.database.Cursor c) {
        com.replymate.core.model.UsageEvent o = new com.replymate.core.model.UsageEvent();
        o.id = c.getLong(c.getColumnIndexOrThrow("id"));
        o.ts = c.getLong(c.getColumnIndexOrThrow("ts"));
        o.model = c.getString(c.getColumnIndexOrThrow("model"));
        o.tokensIn = c.getInt(c.getColumnIndexOrThrow("tokens_in"));
        o.tokensOut = c.getInt(c.getColumnIndexOrThrow("tokens_out"));
        o.kind = com.replymate.core.model.UsageKind.fromWire(
            c.getString(c.getColumnIndexOrThrow("kind")));
        return o;
    }
}
