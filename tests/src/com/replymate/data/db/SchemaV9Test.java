package com.replymate.data.db;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-17: schema v9 — message.item_class (what the item was) and
 *  message.conv_id / conv_title (the message's own conversation identity, the
 *  DeliveryGuard's refusal evidence) are registered and applied exactly once,
 *  in order, never editing older migrations. */
public final class SchemaV9Test {

    /** Tiny in-memory ExecSql that records applied statements. */
    private static final class FakeDb implements ExecSql {
        final List<String> ran = new ArrayList<String>();
        int version = 0;
        @Override public void exec(String sql) { ran.add(sql); }
        @Override public int getUserVersion() { return version; }
        @Override public void setUserVersion(int v) { version = v; }
    }

    @Test public void latestIsV9() {
        assertEquals(9, Migrations.LATEST);
    }

    @Test public void v9AddsClassAndConversationIdentityToMessage() {
        assertEquals(3, SchemaV9.DDL.size());
        assertTrue(SchemaV9.DDL.get(0).contains("ALTER TABLE message ADD COLUMN item_class"));
        assertTrue(SchemaV9.DDL.get(1).contains("ADD COLUMN conv_id"));
        assertTrue(SchemaV9.DDL.get(2).contains("ADD COLUMN conv_title"));
        for (String sql : SchemaV9.DDL) {
            assertTrue("pre-v9 rows default to honestly-unstamped ''",
                sql.contains("DEFAULT ''"));
        }
    }

    @Test public void migratingFromV8AppliesExactlyTheThreeColumnsAndLandsOn9() {
        FakeDb db = new FakeDb();
        db.version = 8;
        Migrations.migrate(db);
        assertEquals(9, db.version);
        assertEquals(3, db.ran.size());
        assertTrue(db.ran.get(0).contains("item_class"));
        assertTrue(db.ran.get(1).contains("conv_id"));
        assertTrue(db.ran.get(2).contains("conv_title"));
    }

    @Test public void migratingFromV7ChainsSenderKeyThenIdentityAndLandsOn9() {
        FakeDb db = new FakeDb();
        db.version = 7;
        Migrations.migrate(db);
        assertEquals(9, db.version);
        assertEquals("v8's one column + v9's three", 4, db.ran.size());
        assertTrue(db.ran.get(0).contains("sender_key"));
    }
}
