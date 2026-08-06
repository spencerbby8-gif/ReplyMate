package com.replymate.data;

import com.replymate.data.db.Migrations;
import com.replymate.data.db.SchemaV1;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** P3 schema v2 against a real SQLite engine: draft.favorite added, and the
 *  contact_channel CHECK rebuilt to accept the new watched-app channels while
 *  preserving every existing row. */
public class MigrationV2Test {

    @BeforeClass public static void loadDriver() throws Exception {
        Class.forName("org.sqlite.JDBC");
    }

    private static Connection memoryDb() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    /** Build a database exactly like a P2 device has it: schema v1, user_version=1,
     *  with real rows in the tables v2 touches. */
    private static MigrationTest.JdbcExecSql v1DeviceDb(Connection c) {
        MigrationTest.JdbcExecSql db = new MigrationTest.JdbcExecSql(c);
        for (String sql : SchemaV1.DDL) db.exec(sql);
        db.setUserVersion(1);
        db.exec("INSERT INTO contact(display_name,created_at,updated_at) VALUES('Ama',1,1)");
        db.exec("INSERT INTO contact(id,display_name,created_at,updated_at) VALUES(2,'Bo',1,1)");
        db.exec("INSERT INTO contact_channel(contact_id,channel,remote_key,last_seen_at)"
            + " VALUES(1,'whatsapp','ama-key',100)");
        db.exec("INSERT INTO contact_channel(contact_id,channel,remote_key,last_seen_at)"
            + " VALUES(2,'telegram','bo-key',200)");
        db.exec("INSERT INTO draft(contact_id,prompt_snapshot,reply_text,model,"
            + "variant_group,status,latency_ms,tokens_in,tokens_out,created_at)"
            + " VALUES(1,'{}','old draft','m','g','copied',10,1,2,111)");
        return db;
    }

    @Test public void v1DataSurvivesMigrationToLatest() throws Exception {
        try (Connection c = memoryDb()) {
            MigrationTest.JdbcExecSql db = v1DeviceDb(c);
            Migrations.migrate(db);
            assertEquals(Migrations.LATEST, db.getUserVersion());
            assertEquals(3, db.getUserVersion());

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT channel, remote_key, last_seen_at FROM contact_channel ORDER BY id")) {
                assertTrue(rs.next());
                assertEquals("whatsapp", rs.getString(1));
                assertEquals("ama-key", rs.getString(2));
                assertEquals(100, rs.getLong(3));
                assertTrue(rs.next());
                assertEquals("telegram", rs.getString(1));
                assertFalse(rs.next());
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT reply_text, favorite FROM draft WHERE id=1")) {
                assertTrue(rs.next());
                assertEquals("old draft", rs.getString(1));
                assertEquals("favorite must default to 0 for old drafts", 0, rs.getInt(2));
            }
        }
    }

    @Test public void widenedCheckAcceptsNewChannelsAndStillRejectsGarbage() throws Exception {
        try (Connection c = memoryDb()) {
            MigrationTest.JdbcExecSql db = v1DeviceDb(c);
            Migrations.migrate(db);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO contact_channel(contact_id,channel,remote_key,last_seen_at)"
                    + " VALUES(1,'slack','ws-1',300)");
                st.executeUpdate("INSERT INTO contact_channel(contact_id,channel,remote_key,last_seen_at)"
                    + " VALUES(1,'signal','sig-1',300)");
                st.executeUpdate("INSERT INTO contact_channel(contact_id,channel,remote_key,last_seen_at)"
                    + " VALUES(1,'x','x-1',300)");
                try {
                    st.executeUpdate(
                        "INSERT INTO contact_channel(contact_id,channel,remote_key,last_seen_at)"
                        + " VALUES(1,'myspace','nope',1)");
                    fail("CHECK must still reject unknown channels");
                } catch (Exception expected) { }
            }
        }
    }

    @Test public void favoriteFlagRoundTrips() throws Exception {
        try (Connection c = memoryDb()) {
            MigrationTest.JdbcExecSql db = v1DeviceDb(c);
            Migrations.migrate(db);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("UPDATE draft SET favorite=1 WHERE id=1");
                try (ResultSet rs = st.executeQuery("SELECT favorite FROM draft WHERE id=1")) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                }
            }
        }
    }

    @Test public void uniqueChannelRemoteKeyConstraintSurvivesRebuild() throws Exception {
        try (Connection c = memoryDb()) {
            MigrationTest.JdbcExecSql db = v1DeviceDb(c);
            Migrations.migrate(db);
            try (Statement st = c.createStatement()) {
                try {
                    st.executeUpdate(
                        "INSERT INTO contact_channel(contact_id,channel,remote_key,last_seen_at)"
                        + " VALUES(2,'whatsapp','ama-key',999)");   // same (channel, remote_key)
                    fail("UNIQUE(channel, remote_key) must survive the rebuild");
                } catch (Exception expected) { }
            }
        }
    }

    @Test public void freshInstallLandsDirectlyOnLatest() throws Exception {
        try (Connection c = memoryDb()) {
            MigrationTest.JdbcExecSql db = new MigrationTest.JdbcExecSql(c);
            Migrations.migrate(db);
            assertEquals(Migrations.LATEST, db.getUserVersion());
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO contact(display_name,created_at,updated_at) VALUES('N',1,1)");
                st.executeUpdate("INSERT INTO contact_channel(contact_id,channel,remote_key,last_seen_at)"
                    + " VALUES(1,'discord','d-1',1)");
                st.executeUpdate("INSERT INTO draft(contact_id,prompt_snapshot,reply_text,model,"
                    + "variant_group,status,latency_ms,tokens_in,tokens_out,created_at)"
                    + " VALUES(1,'{}','hi','m','g','generated',1,1,1,1)");
            }
        }
    }
}
