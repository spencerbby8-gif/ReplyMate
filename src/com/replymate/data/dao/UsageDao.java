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
}
