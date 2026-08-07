package com.replymate.data;

import com.replymate.data.db.Migrations;
import com.replymate.data.db.SchemaV1;
import com.replymate.data.db.SchemaV2;
import com.replymate.data.db.SchemaV3;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/** Schema v4 (provider abstraction) against real SQLite: the gemini-only CHECK and the
 *  hardcoded model default are gone, old provider rows (with their keystore aliases)
 *  survive the rebuild, any provider type storable, no model baked into storage. */
public class MigrationV4Test {

    @BeforeClass public static void loadDriver() throws Exception {
        Class.forName("org.sqlite.JDBC");
    }

    private static Connection memoryDb() throws Exception {
        return DriverManager.getConnection("jdbc:sqlite::memory:");
    }

    /** A 0.5.x device database: v1–v3 applied, one configured Gemini provider. */
    private static MigrationTest.JdbcExecSql v3DeviceDb(Connection c) {
        MigrationTest.JdbcExecSql db = new MigrationTest.JdbcExecSql(c);
        for (String sql : SchemaV1.DDL) db.exec(sql);
        db.setUserVersion(1);
        for (String sql : SchemaV2.DDL) db.exec(sql);
        db.setUserVersion(2);
        for (String sql : SchemaV3.DDL) db.exec(sql);
        db.setUserVersion(3);
        db.exec("INSERT INTO provider_def(type,label,base_url,model_name,key_ref,is_active,created_at)"
            + " VALUES('gemini','Gemini','https://generativelanguage.googleapis.com',"
            + "'gemini-x','gemini.main',1,111)");
        return db;
    }

    @Test public void providerRowAndKeystoreAliasSurviveTheRebuild() throws Exception {
        try (Connection c = memoryDb()) {
            MigrationTest.JdbcExecSql db = v3DeviceDb(c);
            Migrations.migrate(db);
            assertEquals(Migrations.LATEST, db.getUserVersion());

            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                     "SELECT type, label, base_url, model_name, key_ref, is_active"
                     + " FROM provider_def")) {
                assertTrue(rs.next());
                assertEquals("gemini", rs.getString(1));
                assertEquals("Gemini", rs.getString(2));
                assertEquals("https://generativelanguage.googleapis.com", rs.getString(3));
                assertEquals("gemini-x", rs.getString(4));   // user's model choice kept
                assertEquals("gemini.main", rs.getString(5)); // vault alias intact → key not lost
                assertEquals(1, rs.getInt(6));
                assertFalse(rs.next());
            }
        }
    }

    @Test public void anyProviderTypeIsStorableAfterTheUpgrade() throws Exception {
        try (Connection c = memoryDb()) {
            MigrationTest.JdbcExecSql db = v3DeviceDb(c);
            Migrations.migrate(db);
            try (Statement st = c.createStatement()) {
                // the old CHECK('gemini') would reject every one of these
                st.executeUpdate("INSERT INTO provider_def(type,label,base_url,model_name,key_ref,is_active,created_at)"
                    + " VALUES('anthropic','Claude','https://api.anthropic.com','claude-x','p.anthropic',0,1)");
                st.executeUpdate("INSERT INTO provider_def(type,label,base_url,model_name,key_ref,is_active,created_at)"
                    + " VALUES('ollama','Ollama','http://localhost:11434/v1','llama','',0,1)");
                st.executeUpdate("INSERT INTO provider_def(type,label,base_url,model_name,key_ref,is_active,created_at)"
                    + " VALUES('openai_compat','My gateway','https://gw.example/v1','m','p.custom',0,1)");
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM provider_def")) {
                assertTrue(rs.next());
                assertEquals(4, rs.getInt(1));
            }
        }
    }

    @Test public void freshInstallHasNoModelDefaultBakedIn() throws Exception {
        try (Connection c = memoryDb()) {
            MigrationTest.JdbcExecSql db = new MigrationTest.JdbcExecSql(c);
            Migrations.migrate(db);
            assertEquals(Migrations.LATEST, db.getUserVersion());
            try (Statement st = c.createStatement()) {
                // inserting WITHOUT a model_name must default to '' — never to a model id
                st.executeUpdate("INSERT INTO provider_def(type,key_ref,created_at) VALUES('mistral','p.m',1)");
                ResultSet rs = st.executeQuery("SELECT model_name FROM provider_def WHERE type='mistral'");
                assertTrue(rs.next());
                assertEquals("", rs.getString(1));
            }
        }
    }

    @Test public void v3StyleTablesAreUntouchedByTheProviderRebuild() throws Exception {
        try (Connection c = memoryDb()) {
            MigrationTest.JdbcExecSql db = v3DeviceDb(c);
            db.exec("INSERT INTO contact(display_name,created_at,updated_at) VALUES('A',1,1)");
            db.exec("INSERT INTO style_setting(contact_id,key,value,updated_at) VALUES(NULL,'tone','2',5)");
            db.exec("INSERT INTO style_signal(contact_id,kind,detail,created_at) VALUES(1,'approved','',7)");
            Migrations.migrate(db);
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT value FROM style_setting WHERE key='tone'")) {
                assertTrue(rs.next());
                assertEquals("2", rs.getString(1));
            }
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT kind FROM style_signal")) {
                assertTrue(rs.next());
                assertEquals("approved", rs.getString(1));
            }
        }
    }
}
