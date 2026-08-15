package com.replymate.data.db;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-16b: schema v8 — message.sender_key (stable platform sender id)
 *  is registered and applied exactly once, in order, never editing older
 *  migrations. */
public final class SchemaV8Test {

    /** Tiny in-memory ExecSql that records applied statements. */
    private static final class FakeDb implements ExecSql {
        final List<String> ran = new ArrayList<String>();
        int version = 0;
        @Override public void exec(String sql) { ran.add(sql); }
        @Override public int getUserVersion() { return version; }
        @Override public void setUserVersion(int v) { version = v; }
    }

    @Test public void latestIsV8() {
        assertEquals(8, Migrations.LATEST);
    }

    @Test public void v8AddsSenderKeyToMessage() {
        assertEquals(1, SchemaV8.DDL.size());
        String sql = SchemaV8.DDL.get(0);
        assertTrue(sql.contains("ALTER TABLE message ADD COLUMN sender_key"));
        assertTrue(sql.contains("DEFAULT ''"));
    }

    @Test public void migratingFromV7AppliesExactlySenderKeyAndLandsOn8() {
        FakeDb db = new FakeDb();
        db.version = 7;
        Migrations.migrate(db);
        assertEquals(8, db.version);
        assertEquals(1, db.ran.size());
        assertTrue(db.ran.get(0).contains("sender_key"));
    }
}
