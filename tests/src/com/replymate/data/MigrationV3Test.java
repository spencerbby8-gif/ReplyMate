package com.replymate.data;

import com.replymate.data.db.Migrations;
import com.replymate.data.db.SchemaV1;
import com.replymate.data.db.SchemaV2;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** Schema v3 (P4 style_setting + style_signal) against real SQLite: upgrade path,
 *  partial unique indexes, CHECK enforcement, cascade behavior. */
public class MigrationV3Test {

    @BeforeClass public static void loadDriver() throws Exception {
        Class.forName("org.sqlite.JDBC");
    }

    private static Connection memoryDb() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    /** A database exactly like a 0.4.x device: v1+v2 applied, real user rows. */
    private static MigrationTest.JdbcExecSql v2DeviceDb(Connection c) {
        MigrationTest.JdbcExecSql db = new MigrationTest.JdbcExecSql(c);
        for (String sql : SchemaV1.DDL) db.exec(sql);
        db.setUserVersion(1);
        for (String sql : SchemaV2.DDL) db.exec(sql);
        db.setUserVersion(2);
        db.exec("INSERT INTO contact(display_name,created_at,updated_at) VALUES('Ama',1,1)");
        db.exec("INSERT INTO contact(id,display_name,created_at,updated_at) VALUES(2,'Bo',1,1)");
        db.exec("INSERT INTO draft(contact_id,prompt_snapshot,reply_text,model,"
            + "variant_group,status,latency_ms,tokens_in,tokens_out,created_at)"
            + " VALUES(1,'{}','hi','m','g','generated',1,1,1,1)");
        return db;
    }

    @Test public void upgradeKeepsExistingDataAndAddsTables() throws Exception {
        try (Connection c = memoryDb()) {
            MigrationTest.JdbcExecSql db = v2DeviceDb(c);
            Migrations.migrate(db);
            assertEquals(3, db.getUserVersion());
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM contact")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1));
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'style_%'")) {
                java.util.Set<String> found = new java.util.HashSet<String>();
                while (rs.next()) found.add(rs.getString(1));
                assertTrue(found.contains("style_setting"));
                assertTrue(found.contains("style_signal"));
            }
        }
    }

    @Test public void freshInstallLandsOnV3() throws Exception {
        try (Connection c = memoryDb()) {
            MigrationTest.JdbcExecSql db = new MigrationTest.JdbcExecSql(c);
            Migrations.migrate(db);
            assertEquals(3, db.getUserVersion());
        }
    }

    @Test public void globalKeysAreUniqueDespiteNullContactId() throws Exception {
        try (Connection c = memoryDb()) {
            MigrationTest.JdbcExecSql db = v2DeviceDb(c);
            Migrations.migrate(db);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO style_setting(contact_id,key,value,updated_at)"
                    + " VALUES(NULL,'tone','2',1)");
                try {
                    st.executeUpdate("INSERT INTO style_setting(contact_id,key,value,updated_at)"
                        + " VALUES(NULL,'tone','0',1)");
                    fail("partial unique index must reject duplicate global keys");
                } catch (Exception expected) { }
                // …while a DIFFERENT global key and a contact override coexist fine
                st.executeUpdate("INSERT INTO style_setting(contact_id,key,value,updated_at)"
                    + " VALUES(NULL,'emoji','1',1)");
                st.executeUpdate("INSERT INTO style_setting(contact_id,key,value,updated_at)"
                    + " VALUES(1,'tone','0',1)");
                st.executeUpdate("INSERT INTO style_setting(contact_id,key,value,updated_at)"
                    + " VALUES(2,'tone','2',1)");      // same key, different contact — allowed
                try {
                    st.executeUpdate("INSERT INTO style_setting(contact_id,key,value,updated_at)"
                        + " VALUES(1,'tone','2',1)");
                    fail("duplicate (contact,key) must be rejected");
                } catch (Exception expected) { }
            }
        }
    }

    @Test public void signalKindCheckAndCascadeHold() throws Exception {
        try (Connection c = memoryDb()) {
            MigrationTest.JdbcExecSql db = v2DeviceDb(c);
            Migrations.migrate(db);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO style_signal(contact_id,kind,detail,created_at)"
                    + " VALUES(1,'edited','shorter',10)");
                try {
                    st.executeUpdate("INSERT INTO style_signal(contact_id,kind,detail,created_at)"
                        + " VALUES(1,'loved','',10)");
                    fail("CHECK must reject unknown signal kinds");
                } catch (Exception expected) { }

                st.execute("PRAGMA foreign_keys=ON");
                st.executeUpdate("INSERT INTO style_setting(contact_id,key,value,updated_at)"
                    + " VALUES(1,'tone','2',1)");
                st.executeUpdate("DELETE FROM contact WHERE id=1");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM style_signal")) {
                    assertTrue(rs.next());
                    assertEquals("contact delete cascades signals", 0, rs.getInt(1));
                }
                try (ResultSet rs = st.executeQuery(
                        "SELECT COUNT(*) FROM style_setting WHERE contact_id=1")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1));
                }
            }
        }
    }

    @Test public void migrationIsIdempotent() throws Exception {
        try (Connection c = memoryDb()) {
            MigrationTest.JdbcExecSql db = v2DeviceDb(c);
            Migrations.migrate(db);
            assertEquals(3, db.getUserVersion());
            try (Statement st = c.createStatement()) {
                st.executeUpdate("INSERT INTO style_setting(contact_id,key,value,updated_at)"
                    + " VALUES(NULL,'tone','1',1)");
            }
            Migrations.migrate(db);          // second pass must be a no-op
            assertEquals(3, db.getUserVersion());
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM style_setting")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }
}
