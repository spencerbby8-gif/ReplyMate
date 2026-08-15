package com.replymate.data.dao;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.ContactChannel;
import com.replymate.data.db.DbHelper;
import java.util.ArrayList;
import java.util.List;

/** Row access to contact + contact_channel. */
public final class ContactDao {
    private final DbHelper helper;

    public ContactDao(DbHelper helper) { this.helper = helper; }

    public long insert(Contact c) {
        return helper.getWritableDatabase().insert("contact", null, values(c));
    }

    public void update(Contact c) {
        helper.getWritableDatabase().update("contact", values(c),
            "id=?", new String[] {String.valueOf(c.id)});
    }

    private static ContentValues values(Contact c) {
        ContentValues v = new ContentValues();
        v.put("display_name", c.displayName);
        v.put("relationship_type", c.relationshipType);
        v.put("relationship_notes", c.relationshipNotes);
        v.put("tone_override", c.toneOverride);
        v.put("language_pref", c.languagePref);
        if (c.styleProfileId == null) v.putNull("style_profile_id");
        else v.put("style_profile_id", c.styleProfileId);
        v.put("ai_enabled", c.aiEnabled ? 1 : 0);
        v.put("memory_enabled", c.memoryEnabled ? 1 : 0);
        v.put("private_mode", c.privateMode ? 1 : 0);
        v.put("is_group", c.isGroup ? 1 : 0);
        v.put("created_at", c.createdAt);
        v.put("updated_at", c.updatedAt);
        return v;
    }

    public Contact get(long id) {
        Cursor c = helper.getReadableDatabase().query("contact", null, "id=?",
            new String[] {String.valueOf(id)}, null, null, null);
        try {
            return c.moveToFirst() ? contact(c) : null;
        } finally {
            c.close();
        }
    }

    public List<Contact> all() {
        Cursor c = helper.getReadableDatabase().query("contact", null, null, null,
            null, null, "updated_at DESC, id DESC");
        List<Contact> out = new ArrayList<Contact>();
        try {
            while (c.moveToNext()) out.add(contact(c));
        } finally {
            c.close();
        }
        return out;
    }

    public void delete(long id) {
        helper.getWritableDatabase().delete("contact", "id=?",
            new String[] {String.valueOf(id)});
    }

    private static Contact contact(Cursor c) {
        Contact o = new Contact();
        o.id = c.getLong(c.getColumnIndexOrThrow("id"));
        o.displayName = c.getString(c.getColumnIndexOrThrow("display_name"));
        o.relationshipType = c.getString(c.getColumnIndexOrThrow("relationship_type"));
        o.relationshipNotes = c.getString(c.getColumnIndexOrThrow("relationship_notes"));
        o.toneOverride = c.getString(c.getColumnIndexOrThrow("tone_override"));
        o.languagePref = c.getString(c.getColumnIndexOrThrow("language_pref"));
        int sp = c.getColumnIndexOrThrow("style_profile_id");
        o.styleProfileId = c.isNull(sp) ? null : c.getLong(sp);
        o.aiEnabled = c.getInt(c.getColumnIndexOrThrow("ai_enabled")) == 1;
        o.memoryEnabled = c.getInt(c.getColumnIndexOrThrow("memory_enabled")) == 1;
        o.privateMode = c.getInt(c.getColumnIndexOrThrow("private_mode")) == 1;
        o.isGroup = c.getInt(c.getColumnIndexOrThrow("is_group")) == 1;
        o.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        o.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        return o;
    }

    // ---- channels ----

    public List<ContactChannel> channelsByContact(long contactId) {
        Cursor c = helper.getReadableDatabase().query("contact_channel", null, "contact_id=?",
            new String[] {String.valueOf(contactId)}, null, null, null);
        List<ContactChannel> out = new ArrayList<ContactChannel>();
        try {
            while (c.moveToNext()) out.add(channel(c));
        } finally {
            c.close();
        }
        return out;
    }

    public ContactChannel findChannel(Channel channel, String remoteKey) {
        Cursor c = helper.getReadableDatabase().query("contact_channel", null,
            "channel=? AND remote_key=?",
            new String[] {channel.wire, remoteKey}, null, null, null);
        try {
            return c.moveToFirst() ? channel(c) : null;
        } finally {
            c.close();
        }
    }

    public long upsertChannel(ContactChannel ch) {
        SQLiteDatabase db = helper.getWritableDatabase();
        ContactChannel existing = findChannel(ch.channel, ch.remoteKey);
        if (existing != null) {
            ContentValues v = new ContentValues();
            v.put("contact_id", ch.contactId);
            v.put("last_seen_at", ch.lastSeenAt);
            db.update("contact_channel", v, "id=?",
                new String[] {String.valueOf(existing.id)});
            return existing.id;
        }
        ContentValues v = new ContentValues();
        v.put("contact_id", ch.contactId);
        v.put("channel", ch.channel.wire);
        v.put("remote_key", ch.remoteKey);
        v.put("last_seen_at", ch.lastSeenAt);
        return db.insert("contact_channel", null, v);
    }

    public void touchChannel(long channelId, long lastSeenAt) {
        ContentValues v = new ContentValues();
        v.put("last_seen_at", lastSeenAt);
        helper.getWritableDatabase().update("contact_channel", v, "id=?",
            new String[] {String.valueOf(channelId)});
    }

    private static ContactChannel channel(Cursor c) {
        ContactChannel o = new ContactChannel();
        o.id = c.getLong(c.getColumnIndexOrThrow("id"));
        o.contactId = c.getLong(c.getColumnIndexOrThrow("contact_id"));
        o.channel = Channel.fromWire(c.getString(c.getColumnIndexOrThrow("channel")));
        o.remoteKey = c.getString(c.getColumnIndexOrThrow("remote_key"));
        o.lastSeenAt = c.getLong(c.getColumnIndexOrThrow("last_seen_at"));
        return o;
    }

    /** P-ux-fix fork-heal: re-point the duplicate's channel rows at the kept contact;
     *  rows that would collide on UNIQUE(channel, remote_key) are dropped first. */
    public void reassignContact(long fromContactId, long toContactId) {
        android.database.sqlite.SQLiteDatabase db = helper.getWritableDatabase();
        db.delete("contact_channel",
            "contact_id=? AND EXISTS (SELECT 1 FROM contact_channel keep"
                + " WHERE keep.contact_id=? AND keep.channel=contact_channel.channel"
                + " AND keep.remote_key=contact_channel.remote_key)",
            new String[] {String.valueOf(fromContactId), String.valueOf(toContactId)});
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("contact_id", toContactId);
        db.update("contact_channel", v, "contact_id=?",
            new String[] {String.valueOf(fromContactId)});
    }
}
