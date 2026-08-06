package com.replymate.data.dao;

import android.content.ContentValues;
import android.database.Cursor;
import com.replymate.core.model.Scope;
import com.replymate.core.model.StyleProfile;
import com.replymate.data.db.DbHelper;

/** Row access to style_profile (global + per-contact style rules; full editor arrives P5). */
public final class StyleDao {
    private final DbHelper helper;

    public StyleDao(DbHelper helper) { this.helper = helper; }

    public StyleProfile get(Scope scope, Long contactId) {
        String where;
        String[] args;
        if (scope == Scope.GLOBAL) {
            where = "scope='global'";
            args = null;
        } else {
            where = "scope='contact' AND contact_id=?";
            args = new String[] {String.valueOf(contactId)};
        }
        Cursor c = helper.getReadableDatabase().query("style_profile", null, where, args,
            null, null, "id DESC", "1");
        try {
            return c.moveToFirst() ? profile(c) : null;
        } finally {
            c.close();
        }
    }

    public long upsert(StyleProfile p) {
        StyleProfile existing = get(p.scope, p.contactId);
        ContentValues v = new ContentValues();
        v.put("scope", p.scope.wire);
        if (p.contactId == null) v.putNull("contact_id");
        else v.put("contact_id", p.contactId);
        v.put("sample_messages", p.sampleMessages);
        v.put("derived_rules", p.derivedRules);
        v.put("updated_at", p.updatedAt);
        if (existing != null) {
            helper.getWritableDatabase().update("style_profile", v, "id=?",
                new String[] {String.valueOf(existing.id)});
            return existing.id;
        }
        return helper.getWritableDatabase().insert("style_profile", null, v);
    }

    public void deleteByContact(long contactId) {
        helper.getWritableDatabase().delete("style_profile", "contact_id=?",
            new String[] {String.valueOf(contactId)});
    }

    private static StyleProfile profile(Cursor c) {
        StyleProfile o = new StyleProfile();
        o.id = c.getLong(c.getColumnIndexOrThrow("id"));
        o.scope = Scope.fromWire(c.getString(c.getColumnIndexOrThrow("scope")));
        int ci = c.getColumnIndexOrThrow("contact_id");
        o.contactId = c.isNull(ci) ? null : c.getLong(ci);
        o.sampleMessages = c.getString(c.getColumnIndexOrThrow("sample_messages"));
        o.derivedRules = c.getString(c.getColumnIndexOrThrow("derived_rules"));
        o.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        return o;
    }
}
