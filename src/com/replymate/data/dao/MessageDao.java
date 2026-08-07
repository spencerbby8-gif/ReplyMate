package com.replymate.data.dao;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.replymate.core.model.Channel;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.core.model.Source;
import com.replymate.data.db.DbHelper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Row access to message (contact-scoped reads only — isolation rule). */
public final class MessageDao {
    private final DbHelper helper;

    public MessageDao(DbHelper helper) { this.helper = helper; }

    public long insert(Message m) {
        return helper.getWritableDatabase().insert("message", null, values(m));
    }

    /** Insert that silently ignores (channel, notif_key) UNIQUE conflicts — listener path;
     *  returns -1 on a dedupe conflict. */
    public long insertIgnore(Message m) {
        return helper.getWritableDatabase().insertWithOnConflict(
            "message", null, values(m), android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE);
    }

    private static ContentValues values(Message m) {
        ContentValues v = new ContentValues();
        v.put("contact_id", m.contactId);
        v.put("channel", m.channel.wire);
        v.put("direction", m.direction.wire);
        v.put("body", m.body);
        v.put("sent_at", m.sentAt);
        if (m.notifKey == null) v.putNull("notif_key");
        else v.put("notif_key", m.notifKey);
        v.put("source", m.source.wire);
        v.put("content_type", m.contentKind == null ? "" : m.contentKind);
        v.put("media_mime", m.mediaMime == null ? "" : m.mediaMime);
        v.put("media_uri", m.mediaUri == null ? "" : m.mediaUri);
        return v;
    }

    public Message getByNotifKey(Channel channel, String notifKey) {
        if (notifKey == null) return null;
        Cursor c = helper.getReadableDatabase().query("message", null,
            "channel=? AND notif_key=?",
            new String[] {channel.wire, notifKey}, null, null, null);
        try {
            return c.moveToFirst() ? message(c) : null;
        } finally {
            c.close();
        }
    }

    /** Latest {@code limit} messages for the contact, returned oldest-first. */
    public List<Message> lastMessages(long contactId, int limit) {
        Cursor c = helper.getReadableDatabase().query("message", null, "contact_id=?",
            new String[] {String.valueOf(contactId)}, null, null,
            "sent_at DESC, id DESC", String.valueOf(limit));
        List<Message> out = new ArrayList<Message>();
        try {
            while (c.moveToNext()) out.add(message(c));
        } finally {
            c.close();
        }
        Collections.reverse(out);
        return out;
    }

    /** UI inbox search (P3) — see MessageStore.searchByBody for the isolation contract. */
    public List<Message> searchByBody(String query, int limit) {
        List<Message> out = new ArrayList<Message>();
        if (query == null) return out;
        String needle = query.trim();
        if (needle.isEmpty()) return out;
        StringBuilder like = new StringBuilder("%");
        for (int i = 0; i < needle.length(); i++) {
            char ch = needle.charAt(i);
            if (ch == '%' || ch == '_' || ch == '\\') like.append('\\');
            like.append(ch);
        }
        like.append('%');
        Cursor c = helper.getReadableDatabase().query("message", null,
            "body LIKE ? ESCAPE '\\'",
            new String[] {like.toString()}, null, null,
            "sent_at DESC, id DESC", String.valueOf(Math.max(1, limit)));
        try {
            while (c.moveToNext()) out.add(message(c));
        } finally {
            c.close();
        }
        return out;
    }

    public int countByContact(long contactId) {
        Cursor c = helper.getReadableDatabase().rawQuery(
            "SELECT COUNT(*) FROM message WHERE contact_id=?",
            new String[] {String.valueOf(contactId)});
        try {
            return c.moveToFirst() ? c.getInt(0) : 0;
        } finally {
            c.close();
        }
    }

    public void deleteByContact(long contactId) {
        helper.getWritableDatabase().delete("message", "contact_id=?",
            new String[] {String.valueOf(contactId)});
    }

    private static Message message(Cursor c) {
        Message o = new Message();
        o.id = c.getLong(c.getColumnIndexOrThrow("id"));
        o.contactId = c.getLong(c.getColumnIndexOrThrow("contact_id"));
        o.channel = Channel.fromWire(c.getString(c.getColumnIndexOrThrow("channel")));
        o.direction = Direction.fromWire(c.getString(c.getColumnIndexOrThrow("direction")));
        o.body = c.getString(c.getColumnIndexOrThrow("body"));
        o.sentAt = c.getLong(c.getColumnIndexOrThrow("sent_at"));
        int nk = c.getColumnIndexOrThrow("notif_key");
        o.notifKey = c.isNull(nk) ? null : c.getString(nk);
        o.source = Source.fromWire(c.getString(c.getColumnIndexOrThrow("source")));
        o.contentKind = safe(c, "content_type");
        o.mediaMime = safe(c, "media_mime");
        o.mediaUri = safe(c, "media_uri");
        return o;
    }

    /** Column present since schema v5 — read defensively so a pre-v5 database opened
     *  without migration (should never happen; belt and braces) can't crash us. */
    private static String safe(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        if (i < 0 || c.isNull(i)) return "";
        String v = c.getString(i);
        return v == null ? "" : v;
    }
}
