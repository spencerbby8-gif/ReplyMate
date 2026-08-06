package com.replymate.data;

import com.replymate.data.db.ExecSql;
import com.replymate.data.db.Migrations;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** Runs the real migration DDL against a real SQLite engine (JDBC), proving syntax,
 *  structure, FK wiring, indices, and idempotency — the same runner the device uses. */
public class MigrationTest {

    @BeforeClass public static void loadDriver() throws Exception {
        Class.forName("org.sqlite.JDBC");
    }

    /** Platform-neutral ExecSql over a JDBC sqlite connection. */
    static final class JdbcExecSql implements ExecSql {
        final Connection conn;

        JdbcExecSql(Connection conn) { this.conn = conn; }

        @Override public void exec(String sql) {
            try (Statement st = conn.createStatement()) { st.execute(sql); }
            catch (Exception e) { throw new RuntimeException("exec failed: " + sql, e); }
        }

        @Override public int getUserVersion() {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (Exception e) { throw new RuntimeException(e); }
        }

        @Override public void setUserVersion(int v) {
            try (Statement st = conn.createStatement()) { st.executeUpdate("PRAGMA user_version=" + v); }
            catch (Exception e) { throw new RuntimeException(e); }
        }
    }

    private static Connection memoryDb() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    @Test public void freshDatabaseMigratesToLatest() throws Exception {
        try (Connection c = memoryDb()) {
            JdbcExecSql db = new JdbcExecSql(c);
            assertEquals(0, db.getUserVersion());
            Migrations.migrate(db);
            assertEquals(Migrations.LATEST, db.getUserVersion());
        }
    }

    @Test public void allNineTablesExist() throws Exception {
        Set<String> expected = new HashSet<String>(Arrays.asList(
            "contact", "contact_channel", "message", "memory_fact", "contact_summary",
            "style_profile", "provider_def", "draft", "usage_event", "app_kv"));
        // note: spec counts 9 app tables; app_kv is the 10th — we assert the full set present
        try (Connection c = memoryDb()) {
            Migrations.migrate(new JdbcExecSql(c));
            Set<String> found = new HashSet<String>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")) {
                while (rs.next()) found.add(rs.getString(1));
            }
            for (String t : expected) assertTrue("missing table " + t, found.contains(t));
        }
    }

    @Test public void indicesExist() throws Exception {
        try (Connection c = memoryDb()) {
            Migrations.migrate(new JdbcExecSql(c));
            Set<String> idx = new HashSet<String>();
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT name FROM sqlite_master WHERE type='index'")) {
                while (rs.next()) idx.add(rs.getString(1));
            }
            assertTrue(idx.contains("idx_message_contact_ts"));
            assertTrue(idx.contains("idx_fact_contact_active"));
            assertTrue(idx.contains("idx_draft_contact_ts"));
            assertTrue(idx.contains("idx_usage_ts"));
        }
    }

    @Test public void foreignKeysAndCascadeWork() throws Exception {
        try (Connection c = memoryDb()) {
            Migrations.migrate(new JdbcExecSql(c));
            try (Statement st = c.createStatement()) {
                st.execute("PRAGMA foreign_keys=ON");
                st.executeUpdate("INSERT INTO contact(display_name,created_at,updated_at) VALUES('Ama',1,1)");
                st.executeUpdate("INSERT INTO message(contact_id,channel,direction,body,sent_at,source)"
                    + " VALUES(1,'manual','in','hello',100,'manual')");
                st.executeUpdate("INSERT INTO memory_fact(contact_id,category,text,text_norm,created_at,updated_at)"
                    + " VALUES(1,'preference','hates voice notes','hates voice notes',1,1)");
                st.executeUpdate("DELETE FROM contact WHERE id=1");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM message")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1));   // cascaded
                }
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM memory_fact")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1));   // cascaded
                }
            }
        }
    }

    @Test public void uniqueMergeKeyAndNotifDedupe() throws Exception {
        try (Connection c = memoryDb()) {
            Migrations.migrate(new JdbcExecSql(c));
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO contact(display_name,created_at,updated_at) VALUES('Bo',1,1)");
                // (contact_id, text_norm) must be unique
                st.executeUpdate("INSERT INTO memory_fact(contact_id,category,text,text_norm,created_at,updated_at)"
                    + " VALUES(1,'person','likes tea','likes tea',1,1)");
                try {
                    st.executeUpdate("INSERT INTO memory_fact(contact_id,category,text,text_norm,created_at,updated_at)"
                        + " VALUES(1,'person','LIKES TEA','likes tea',1,1)");
                    fail("expected unique constraint on (contact_id, text_norm)");
                } catch (Exception expected) { }

                // notif_key dedupe unique per channel — but multiple NULLs allowed (manual rows)
                st.executeUpdate("INSERT INTO message(contact_id,channel,direction,body,sent_at,source)"
                    + " VALUES(1,'manual','in','m1',1,'manual')");
                st.executeUpdate("INSERT INTO message(contact_id,channel,direction,body,sent_at,source)"
                    + " VALUES(1,'manual','in','m2',2,'manual')");   // second NULL notif_key is fine
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM message")) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1));
                }
            }
        }
    }

    @Test public void migrateTwiceIsIdempotentNoop() throws Exception {
        try (Connection c = memoryDb()) {
            JdbcExecSql db = new JdbcExecSql(c);
            Migrations.migrate(db);
            int v1 = db.getUserVersion();
            Migrations.migrate(db);          // must not throw or change anything
            assertEquals(v1, db.getUserVersion());
        }
    }

    @Test public void checkConstraintsRejectBadValues() throws Exception {
        try (Connection c = memoryDb()) {
            Migrations.migrate(new JdbcExecSql(c));
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO contact(display_name,created_at,updated_at) VALUES('Ck',1,1)");
                try {
                    st.executeUpdate("INSERT INTO message(contact_id,channel,direction,body,sent_at,source)"
                        + " VALUES(1,'manual','sideways','x',1,'manual')");
                    fail("direction CHECK should reject");
                } catch (Exception expected) { }
                try {
                    st.executeUpdate("INSERT INTO memory_fact(contact_id,category,text,text_norm,created_at,updated_at)"
                        + " VALUES(1,'nonsense','x','x',1,1)");
                    fail("category CHECK should reject");
                } catch (Exception expected) { }
            }
        }
    }
}
