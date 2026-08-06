package com.replymate.data.dao;

import android.content.ContentValues;
import android.database.Cursor;
import com.replymate.core.model.Draft;
import com.replymate.core.model.DraftStatus;
import com.replymate.data.db.DbHelper;
import java.util.ArrayList;
import java.util.List;

/** Row access to draft (audit trail: prompt snapshots live here). */
public final class DraftDao {
    private final DbHelper helper;

    public DraftDao(DbHelper helper) { this.helper = helper; }

    public long insert(Draft d) {
        ContentValues v = new ContentValues();
        v.put("contact_id", d.contactId);
        if (d.inReplyToId == null) v.putNull("in_reply_to_id");
        else v.put("in_reply_to_id", d.inReplyToId);
        v.put("prompt_snapshot", d.promptSnapshotJson);
        v.put("reply_text", d.replyText);
        v.put("model", d.model);
        v.put("variant_group", d.variantGroup);
        v.put("status", d.status.wire);
        v.put("latency_ms", d.latencyMs);
        v.put("tokens_in", d.tokensIn);
        v.put("tokens_out", d.tokensOut);
        v.put("created_at", d.createdAt);
        return helper.getWritableDatabase().insert("draft", null, v);
    }

    public List<Draft> byContact(long contactId, int limit) {
        Cursor c = helper.getReadableDatabase().query("draft", null, "contact_id=?",
            new String[] {String.valueOf(contactId)}, null, null,
            "created_at DESC, id DESC", String.valueOf(limit));
        List<Draft> out = new ArrayList<Draft>();
        try {
            while (c.moveToNext()) out.add(draft(c));
        } finally {
            c.close();
        }
        return out;
    }

    public List<Draft> byVariantGroup(String variantGroup) {
        Cursor c = helper.getReadableDatabase().query("draft", null, "variant_group=?",
            new String[] {variantGroup}, null, null, "id ASC");
        List<Draft> out = new ArrayList<Draft>();
        try {
            while (c.moveToNext()) out.add(draft(c));
        } finally {
            c.close();
        }
        return out;
    }

    public void updateStatus(long draftId, DraftStatus status) {
        ContentValues v = new ContentValues();
        v.put("status", status.wire);
        helper.getWritableDatabase().update("draft", v, "id=?",
            new String[] {String.valueOf(draftId)});
    }

    public void updateText(long draftId, String newText) {
        ContentValues v = new ContentValues();
        v.put("reply_text", newText);
        helper.getWritableDatabase().update("draft", v, "id=?",
            new String[] {String.valueOf(draftId)});
    }

    public void deleteByContact(long contactId) {
        helper.getWritableDatabase().delete("draft", "contact_id=?",
            new String[] {String.valueOf(contactId)});
    }

    private static Draft draft(Cursor c) {
        Draft o = new Draft();
        o.id = c.getLong(c.getColumnIndexOrThrow("id"));
        o.contactId = c.getLong(c.getColumnIndexOrThrow("contact_id"));
        int rt = c.getColumnIndexOrThrow("in_reply_to_id");
        o.inReplyToId = c.isNull(rt) ? null : c.getLong(rt);
        o.promptSnapshotJson = c.getString(c.getColumnIndexOrThrow("prompt_snapshot"));
        o.replyText = c.getString(c.getColumnIndexOrThrow("reply_text"));
        o.model = c.getString(c.getColumnIndexOrThrow("model"));
        o.variantGroup = c.getString(c.getColumnIndexOrThrow("variant_group"));
        o.status = DraftStatus.fromWire(c.getString(c.getColumnIndexOrThrow("status")));
        o.latencyMs = c.getLong(c.getColumnIndexOrThrow("latency_ms"));
        o.tokensIn = c.getInt(c.getColumnIndexOrThrow("tokens_in"));
        o.tokensOut = c.getInt(c.getColumnIndexOrThrow("tokens_out"));
        o.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return o;
    }
}
