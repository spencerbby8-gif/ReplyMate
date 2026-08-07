package com.replymate.data.dao;

import android.content.ContentValues;
import android.database.Cursor;
import com.replymate.core.model.StyleSignal;
import com.replymate.data.db.DbHelper;
import java.util.ArrayList;
import java.util.List;

/** Row access to style_signal (P4 learning). Contact-scoped only. */
public final class StyleSignalDao {
    private final DbHelper helper;

    public StyleSignalDao(DbHelper helper) { this.helper = helper; }

    public long insert(StyleSignal s) {
        ContentValues v = new ContentValues();
        v.put("contact_id", s.contactId);
        v.put("kind", s.kind.wire);
        v.put("detail", s.detail == null ? "" : s.detail);
        if (s.draftId == null) v.putNull("draft_id");
        else v.put("draft_id", s.draftId);
        v.put("created_at", s.createdAt);
        return helper.getWritableDatabase().insert("style_signal", null, v);
    }

    public List<StyleSignal> byContact(long contactId, int limit) {
        Cursor c = helper.getReadableDatabase().query("style_signal", null,
            "contact_id=?", new String[] {String.valueOf(contactId)},
            null, null, "created_at DESC, id DESC", String.valueOf(Math.max(1, limit)));
        List<StyleSignal> out = new ArrayList<StyleSignal>();
        try {
            while (c.moveToNext()) out.add(signal(c));
        } finally {
            c.close();
        }
        return out;
    }

    public void deleteForContact(long contactId) {
        helper.getWritableDatabase().delete("style_signal", "contact_id=?",
            new String[] {String.valueOf(contactId)});
    }

    private static StyleSignal signal(Cursor c) {
        StyleSignal s = new StyleSignal();
        s.id = c.getLong(c.getColumnIndexOrThrow("id"));
        s.contactId = c.getLong(c.getColumnIndexOrThrow("contact_id"));
        s.kind = StyleSignal.Kind.fromWire(c.getString(c.getColumnIndexOrThrow("kind")));
        s.detail = c.getString(c.getColumnIndexOrThrow("detail"));
        int d = c.getColumnIndexOrThrow("draft_id");
        s.draftId = c.isNull(d) ? null : c.getLong(d);
        s.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return s;
    }

    /** P-ux-fix fork-heal: move all rows from a merged-away duplicate contact. */
    public void reassignContact(long fromContactId, long toContactId) {
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("contact_id", toContactId);
        helper.getWritableDatabase().update("style_signal", v, "contact_id=?",
            new String[] {String.valueOf(fromContactId)});
    }
}
