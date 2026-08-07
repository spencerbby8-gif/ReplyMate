package com.replymate.data.dao;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.replymate.data.db.DbHelper;
import java.util.HashMap;
import java.util.Map;

/** Row access to style_setting (P4). contactId null → GLOBAL rows. */
public final class StyleSettingDao {
    private final DbHelper helper;

    public StyleSettingDao(DbHelper helper) { this.helper = helper; }

    public Map<String, String> all(Long contactId) {
        String where;
        String[] args;
        if (contactId == null) {
            where = "contact_id IS NULL";
            args = null;
        } else {
            where = "contact_id=?";
            args = new String[] {String.valueOf(contactId)};
        }
        Cursor c = helper.getReadableDatabase().query("style_setting",
            new String[] {"key", "value"}, where, args, null, null, null);
        Map<String, String> out = new HashMap<String, String>();
        try {
            while (c.moveToNext()) out.put(c.getString(0), c.getString(1));
        } finally {
            c.close();
        }
        return out;
    }

    /** Insert-or-update within one transaction (partial unique indexes back this). */
    public void put(Long contactId, String key, String value, long updatedAt) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues v = new ContentValues();
            if (contactId == null) v.putNull("contact_id");
            else v.put("contact_id", contactId);
            v.put("key", key);
            v.put("value", value);
            v.put("updated_at", updatedAt);
            String where = contactId == null
                ? "contact_id IS NULL AND key=?" : "contact_id=? AND key=?";
            String[] args = contactId == null
                ? new String[] {key} : new String[] {String.valueOf(contactId), key};
            int n = db.update("style_setting", v, where, args);
            if (n == 0) db.insert("style_setting", null, v);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void remove(Long contactId, String key) {
        String where = contactId == null
            ? "contact_id IS NULL AND key=?" : "contact_id=? AND key=?";
        String[] args = contactId == null
            ? new String[] {key} : new String[] {String.valueOf(contactId), key};
        helper.getWritableDatabase().delete("style_setting", where, args);
    }

    /** P-ux-fix fork-heal: move the duplicate's per-contact style rows over; on a key
     *  collision the KEPT contact's value wins and the duplicate's row is dropped. */
    public void reassignContact(long fromContactId, long toContactId) {
        android.database.sqlite.SQLiteDatabase db = helper.getWritableDatabase();
        db.delete("style_setting",
            "contact_id=? AND key IN (SELECT key FROM style_setting WHERE contact_id=?)",
            new String[] {String.valueOf(fromContactId), String.valueOf(toContactId)});
        android.content.ContentValues v = new android.content.ContentValues();
        v.put("contact_id", toContactId);
        db.update("style_setting", v, "contact_id=?",
            new String[] {String.valueOf(fromContactId)});
    }
}
