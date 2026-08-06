package com.replymate.data.dao;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.replymate.core.model.ContactSummary;
import com.replymate.core.model.FactCategory;
import com.replymate.core.model.MemoryFact;
import com.replymate.data.db.DbHelper;
import java.util.ArrayList;
import java.util.List;

/** Row access to memory_fact + contact_summary (contact-scoped only — isolation). */
public final class MemoryDao {
    private final DbHelper helper;

    public MemoryDao(DbHelper helper) { this.helper = helper; }

    /* ------------------------------------------------------------------ facts */

    public List<MemoryFact> activeFacts(long contactId) {
        return queryFacts("contact_id=? AND disabled=0",
            new String[] {String.valueOf(contactId)}, "pinned DESC, importance DESC, updated_at DESC");
    }

    public List<MemoryFact> allFacts(long contactId) {
        return queryFacts("contact_id=?",
            new String[] {String.valueOf(contactId)}, "pinned DESC, importance DESC, updated_at DESC");
    }

    private List<MemoryFact> queryFacts(String where, String[] args, String order) {
        Cursor c = helper.getReadableDatabase().query(
            "memory_fact", null, where, args, null, null, order);
        List<MemoryFact> out = new ArrayList<MemoryFact>();
        try {
            while (c.moveToNext()) out.add(fact(c));
        } finally {
            c.close();
        }
        return out;
    }

    /** Read-modify-write merge on (contact_id, text_norm): UPDATE when the key exists,
     *  INSERT otherwise. Keeps row id + pin state stable; runs in one transaction. */
    public long upsertFact(MemoryFact f) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            Cursor c = db.query("memory_fact", new String[] {"id"},
                "contact_id=? AND text_norm=?",
                new String[] {String.valueOf(f.contactId), f.textNorm},
                null, null, null);
            long existingId = -1;
            try {
                if (c.moveToFirst()) existingId = c.getLong(0);
            } finally {
                c.close();
            }
            ContentValues v = factValues(f);
            long id;
            if (existingId > 0) {
                db.update("memory_fact", v, "id=?",
                    new String[] {String.valueOf(existingId)});
                id = existingId;
            } else {
                id = db.insert("memory_fact", null, v);
            }
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public void setFactPinned(long factId, boolean pinned) {
        flag(factId, "pinned", pinned);
    }

    public void setFactDisabled(long factId, boolean disabled) {
        flag(factId, "disabled", disabled);
    }

    private void flag(long factId, String column, boolean value) {
        ContentValues v = new ContentValues();
        v.put(column, value ? 1 : 0);
        helper.getWritableDatabase().update("memory_fact", v, "id=?",
            new String[] {String.valueOf(factId)});
    }

    public void deleteFact(long factId) {
        helper.getWritableDatabase().delete("memory_fact", "id=?",
            new String[] {String.valueOf(factId)});
    }

    public void deleteFactsForContact(long contactId) {
        helper.getWritableDatabase().delete("memory_fact", "contact_id=?",
            new String[] {String.valueOf(contactId)});
    }

    /* --------------------------------------------------------------- summaries */

    public ContactSummary latestSummary(long contactId) {
        Cursor c = helper.getReadableDatabase().query("contact_summary", null,
            "contact_id=?", new String[] {String.valueOf(contactId)},
            null, null, "version DESC", "1");
        try {
            return c.moveToFirst() ? summary(c) : null;
        } finally {
            c.close();
        }
    }

    public long insertSummary(ContactSummary s) {
        ContentValues v = new ContentValues();
        v.put("contact_id", s.contactId);
        v.put("summary_text", s.summaryText);
        v.put("covers_until_ts", s.coversUntilTs);
        v.put("version", s.version);
        v.put("created_at", s.createdAt);
        return helper.getWritableDatabase().insert("contact_summary", null, v);
    }

    public void deleteSummariesForContact(long contactId) {
        helper.getWritableDatabase().delete("contact_summary", "contact_id=?",
            new String[] {String.valueOf(contactId)});
    }

    /* ------------------------------------------------------------------ mappers */

    private static ContentValues factValues(MemoryFact f) {
        ContentValues v = new ContentValues();
        v.put("contact_id", f.contactId);
        v.put("category", f.category.wire);
        v.put("text", f.text);
        v.put("text_norm", f.textNorm);
        v.put("importance", f.importance);
        v.put("confidence", f.confidence);
        v.put("pinned", f.pinned ? 1 : 0);
        v.put("disabled", f.disabled ? 1 : 0);
        if (f.sourceMessageId == null) v.putNull("source_message_id");
        else v.put("source_message_id", f.sourceMessageId);
        v.put("created_at", f.createdAt);
        v.put("updated_at", f.updatedAt);
        return v;
    }

    private static MemoryFact fact(Cursor c) {
        MemoryFact f = new MemoryFact();
        f.id = c.getLong(c.getColumnIndexOrThrow("id"));
        f.contactId = c.getLong(c.getColumnIndexOrThrow("contact_id"));
        f.category = FactCategory.fromWire(c.getString(c.getColumnIndexOrThrow("category")));
        f.text = c.getString(c.getColumnIndexOrThrow("text"));
        f.textNorm = c.getString(c.getColumnIndexOrThrow("text_norm"));
        f.importance = c.getInt(c.getColumnIndexOrThrow("importance"));
        f.confidence = c.getDouble(c.getColumnIndexOrThrow("confidence"));
        f.pinned = c.getInt(c.getColumnIndexOrThrow("pinned")) == 1;
        f.disabled = c.getInt(c.getColumnIndexOrThrow("disabled")) == 1;
        int src = c.getColumnIndexOrThrow("source_message_id");
        f.sourceMessageId = c.isNull(src) ? null : c.getLong(src);
        f.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        f.updatedAt = c.getLong(c.getColumnIndexOrThrow("updated_at"));
        return f;
    }

    private static ContactSummary summary(Cursor c) {
        ContactSummary s = new ContactSummary();
        s.id = c.getLong(c.getColumnIndexOrThrow("id"));
        s.contactId = c.getLong(c.getColumnIndexOrThrow("contact_id"));
        s.summaryText = c.getString(c.getColumnIndexOrThrow("summary_text"));
        s.coversUntilTs = c.getLong(c.getColumnIndexOrThrow("covers_until_ts"));
        s.version = c.getInt(c.getColumnIndexOrThrow("version"));
        s.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return s;
    }
}
