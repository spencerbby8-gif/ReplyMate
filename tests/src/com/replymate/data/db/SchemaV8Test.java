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

    @Test public void v8IsSupersededButNeverEdited() {
        assertTrue("schema v9 (P-intelligence-17) is the latest; v8's DDL and"
            + " position in the chain stay untouched", Migrations.LATEST > 8);
    }

    @Test public void v8AddsSenderKeyToMessage() {
        assertEquals(1, SchemaV8.DDL.size());
        String sql = SchemaV8.DDL.get(0);
        assertTrue(sql.contains("ALTER TABLE message ADD COLUMN sender_key"));
        assertTrue(sql.contains("DEFAULT ''"));
    }

    @Test public void migratingFromV7AppliesSenderKeyFirstThenMigratesOnToLatest() {
        FakeDb db = new FakeDb();
        db.version = 7;
        Migrations.migrate(db);
        assertEquals(Migrations.LATEST, db.version);
        assertTrue("v8's sender_key is still applied FIRST, in chain order",
            db.ran.get(0).contains("sender_key"));
    }
}
