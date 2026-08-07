package com.replymate.data;

import com.replymate.data.db.Migrations;
import com.replymate.data.db.SchemaV1;
import com.replymate.data.db.SchemaV2;
import com.replymate.data.db.SchemaV3;
import com.replymate.data.db.SchemaV4;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** Schema v5 (P-audit-deep media pipeline) against real SQLite: the message table
 *  gains content_type / media_mime / media_uri with "" defaults, every pre-v5 row
 *  keeps its data untouched (kind is inferred from body shape at read time), and
 *  fresh installs carry all three columns. */
public class MigrationV5Test {

    @BeforeClass public static void loadDriver() throws Exception {
        Class.forName("org.sqlite.JDBC");
    }

    private static Connection memoryDb() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    /** A 0.8.x device database: v1–v4 applied, one text + one legacy media row. */
    private static MigrationTest.JdbcExecSql v4DeviceDb(Connection c) {
        MigrationTest.JdbcExecSql db = new MigrationTest.JdbcExecSql(c);
        for (String sql : SchemaV1.DDL) db.exec(sql);
        db.setUserVersion(1);
        for (String sql : SchemaV2.DDL) db.exec(sql);
        db.setUserVersion(2);
        for (String sql : SchemaV3.DDL) db.exec(sql);
        db.setUserVersion(3);
        for (String sql : SchemaV4.DDL) db.exec(sql);
        db.setUserVersion(4);
        db.exec("INSERT INTO contact(display_name,created_at,updated_at) VALUES('Amara',1,1)");
        db.exec("INSERT INTO message(contact_id,channel,direction,body,sent_at,source)"
            + " VALUES(1,'whatsapp','in','hello there',100,'listener')");
        db.exec("INSERT INTO message(contact_id,channel,direction,body,sent_at,source)"
            + " VALUES(1,'whatsapp','in','[media/voice — open in chat app]',200,'listener')");
        return db;
    }

    @Test public void upgradeAddsTheThreeColumnsWithEmptyDefaults() throws Exception {
        try (Connection c = memoryDb()) {
            MigrationTest.JdbcExecSql db = v4DeviceDb(c);
            Migrations.migrate(db);
            assertEquals(Migrations.LATEST, db.getUserVersion());
            assertTrue("v5 must remain a shipped, immutable step", Migrations.LATEST >= 5);

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT body, content_type, media_mime, media_uri FROM message ORDER BY sent_at")) {
                assertTrue(rs.next());
                assertEquals("hello there", rs.getString(1));
                assertEquals("", rs.getString(2));     // legacy row: kind NOT rewritten
                assertEquals("", rs.getString(3));
                assertEquals("", rs.getString(4));
                assertTrue(rs.next());
                assertEquals("[media/voice — open in chat app]", rs.getString(1)); // body intact
                assertEquals("", rs.getString(2));
            }
        }
    }

    @Test public void newKindedRowsRoundTripThroughTheNewColumns() throws Exception {
        try (Connection c = memoryDb()) {
            MigrationTest.JdbcExecSql db = v4DeviceDb(c);
            Migrations.migrate(db);
            db.exec("INSERT INTO message(contact_id,channel,direction,body,sent_at,source,"
                + "content_type,media_mime,media_uri)"
                + " VALUES(1,'whatsapp','in','[photo — open in the chat app to view]',300,"
                + "'listener','image','image/jpeg','content://wa/123')");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT content_type, media_mime, media_uri FROM message"
                     + " WHERE content_type='image'")) {
                assertTrue(rs.next());
                assertEquals("image", rs.getString(1));
                assertEquals("image/jpeg", rs.getString(2));
                assertEquals("content://wa/123", rs.getString(3));
            }
        }
    }

    @Test public void freshInstallCarriesTheColumns() throws Exception {
        try (Connection c = memoryDb()) {
            MigrationTest.JdbcExecSql db = new MigrationTest.JdbcExecSql(c);
            Migrations.migrate(db);
            assertEquals(Migrations.LATEST, db.getUserVersion());
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA table_info(message)")) {
                java.util.Set<String> cols = new java.util.HashSet<String>();
                while (rs.next()) cols.add(rs.getString(2));
                assertTrue(cols.contains("content_type"));
                assertTrue(cols.contains("media_mime"));
                assertTrue(cols.contains("media_uri"));
            }
        }
    }

    @Test public void dedupeConstraintSurvivesTheMessageAlter() throws Exception {
        try (Connection c = memoryDb()) {
            MigrationTest.JdbcExecSql db = v4DeviceDb(c);
            db.exec("INSERT INTO message(contact_id,channel,direction,body,sent_at,notif_key,source)"
                + " VALUES(1,'whatsapp','in','x',50,'k1','listener')");
            Migrations.migrate(db);
            // re-apply is idempotent; a duplicate notif_key INSERT IGNORE stays a no-op
            db.exec("INSERT OR IGNORE INTO message(contact_id,channel,direction,body,sent_at,notif_key,source)"
                + " VALUES(1,'whatsapp','in','x',50,'k1','listener')");
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM message WHERE notif_key='k1'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }
}
