package com.replymate.data.dao;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.replymate.core.model.ProviderDef;
import com.replymate.core.model.ProviderType;
import com.replymate.data.db.DbHelper;

/** Row access to provider_def (NON-SECRET config only — keys never touch this table). */
public final class ProviderDao {
    private final DbHelper helper;

    public ProviderDao(DbHelper helper) { this.helper = helper; }

    /** Save def (by type: one row per provider type), make it the single active row. */
    public long upsertActive(ProviderDef def) {
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues off = new ContentValues();
            off.put("is_active", 0);
            db.update("provider_def", off, null, null);

            ContentValues v = values(def);
            v.put("is_active", 1);
            Long existingId = idByType(def.type);
            long id;
            if (existingId != null) {
                db.update("provider_def", v, "id=?",
                    new String[] {String.valueOf(existingId)});
                id = existingId;
            } else {
                id = db.insert("provider_def", null, v);
            }
            db.setTransactionSuccessful();
            return id;
        } finally {
            db.endTransaction();
        }
    }

    public Long idByType(ProviderType type) {
        Cursor c = helper.getReadableDatabase().query("provider_def", new String[] {"id"},
            "type=?", new String[] {type.wire}, null, null, null);
        try {
            return c.moveToFirst() ? c.getLong(0) : null;
        } finally {
            c.close();
        }
    }

    public ProviderDef active() {
        Cursor c = helper.getReadableDatabase().query("provider_def", null, "is_active=1",
            null, null, null, null, "1");
        try {
            return c.moveToFirst() ? def(c) : null;
        } finally {
            c.close();
        }
    }

    private static ContentValues values(ProviderDef d) {
        ContentValues v = new ContentValues();
        v.put("type", d.type.wire);
        v.put("label", d.label);
        v.put("base_url", d.baseUrl);
        v.put("model_name", d.modelName);
        v.put("key_ref", d.keyRef);
        v.put("created_at", d.createdAt > 0 ? d.createdAt : System.currentTimeMillis());
        return v;
    }

    private static ProviderDef def(Cursor c) {
        ProviderDef o = new ProviderDef();
        o.id = c.getLong(c.getColumnIndexOrThrow("id"));
        o.type = ProviderType.fromWire(c.getString(c.getColumnIndexOrThrow("type")));
        o.label = c.getString(c.getColumnIndexOrThrow("label"));
        o.baseUrl = c.getString(c.getColumnIndexOrThrow("base_url"));
        o.modelName = c.getString(c.getColumnIndexOrThrow("model_name"));
        o.keyRef = c.getString(c.getColumnIndexOrThrow("key_ref"));
        o.isActive = c.getInt(c.getColumnIndexOrThrow("is_active")) == 1;
        o.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
        return o;
    }
}
