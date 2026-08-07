package com.replymate.data;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

/** Schema v6 (P-memory-audit): message gains sender_name for per-message sender
 *  attribution (group chats). Existing rows default to "" — nothing is rewritten. */
public class MigrationV6Test {

    private static Connection migrateTo(int target) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
        Statement st = c.createStatement();
        st.execute("PRAGMA user_version = 0");
        com.replymate.data.db.ExecSql exec = new com.replymate.data.db.ExecSql() {
            @Override public void exec(String sql) {
                try { st.execute(sql); } catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override public int getUserVersion() {
                try {
                    ResultSet r = st.executeQuery("PRAGMA user_version");
                    return r.next() ? r.getInt(1) : 0;
                } catch (Exception e) { throw new RuntimeException(e); }
            }
            @Override public void setUserVersion(int v) {
                try { st.execute("PRAGMA user_version = " + v); }
                catch (Exception e) { throw new RuntimeException(e); }
            }
        };
        // apply migrations up to and including target via the REAL registry
        List<com.replymate.data.db.Migrations.Migration> all =
            com.replymate.data.db.Migrations.all();
        for (com.replymate.data.db.Migrations.Migration m : all) {
            if (m.version() <= target) {
                for (String sql : m.statements()) exec.exec(sql);
                exec.setUserVersion(m.version());
            }
        }
        return c;
    }

    @Test public void senderNameColumnExistsAfterMigration() throws Exception {
        Connection c = migrateTo(com.replymate.data.db.Migrations.LATEST);
        Statement st = c.createStatement();
        ResultSet cols = st.executeQuery("PRAGMA table_info(message)");
        boolean found = false;
        while (cols.next()) {
            if ("sender_name".equals(cols.getString("name"))) {
                found = true;
                assertEquals("", cols.getString("dflt_value").replace("'", ""));
            }
        }
        assertTrue("sender_name column must exist", found);
        ResultSet v = st.executeQuery("PRAGMA user_version");
        assertEquals(com.replymate.data.db.Migrations.LATEST, v.getInt(1));
        c.close();
    }

    @Test public void preExistingRowsKeepWorkingWithEmptySender() throws Exception {
        Connection c = migrateTo(5);   // stop BEFORE v6
        Statement st = c.createStatement();
        st.execute("INSERT INTO style_profile(scope, updated_at) VALUES('global', 1)");
        st.execute("INSERT INTO contact(display_name, created_at, updated_at)"
            + " VALUES('Ada', 1, 1)");
        st.execute("INSERT INTO message(contact_id, channel, direction, body, sent_at,"
            + " source, content_type, media_mime, media_uri)"
            + " VALUES(1, 'whatsapp', 'in', 'legacy text', 100, 'listener', '', '', '')");
        // now apply v6 on top
        ResultSet before = st.executeQuery("SELECT body FROM message WHERE id=1");
        assertTrue(before.next());
        for (String sql : com.replymate.data.db.SchemaV6.DDL) st.execute(sql);
        st.execute("PRAGMA user_version = 6");
        ResultSet after = st.executeQuery("SELECT body, sender_name FROM message WHERE id=1");
        assertTrue(after.next());
        assertEquals("legacy text", after.getString("body"));
        assertEquals("", after.getString("sender_name"));
        c.close();
    }

    @Test public void fullRegistryBuildsFromScratch() throws Exception {
        Connection c = migrateTo(com.replymate.data.db.Migrations.LATEST);
        Statement st = c.createStatement();
        st.execute("INSERT INTO style_profile(scope, updated_at) VALUES('global', 1)");
        st.execute("INSERT INTO contact(display_name, created_at, updated_at)"
            + " VALUES('Ada', 1, 1)");
        st.execute("INSERT INTO message(contact_id, channel, direction, body, sent_at,"
            + " source, sender_name) VALUES(1, 'whatsapp', 'in', 'group text', 100,"
            + " 'listener', 'Kunle')");
        ResultSet r = st.executeQuery("SELECT sender_name FROM message WHERE id=1");
        assertTrue(r.next());
        assertEquals("Kunle", r.getString("sender_name"));
        c.close();
    }
}
