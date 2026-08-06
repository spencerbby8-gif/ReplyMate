package com.replymate.data;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Device-crash documentation test (P0 hotfix 0.0.2).
 *
 * Proves WHY v0.0.1 died on a physical device but not here:
 *   PRAGMA journal_mode=WAL  *returns a result row* ("wal").
 *   On Android, SQLiteDatabase.execSQL() routes through nativeExecute, which
 *   throws SQLiteException ("Queries can be performed using SQLiteDatabase
 *   query or rawQuery methods only.") for ANY row-returning statement.
 *   Our old Pragmas.apply() called exactly that inside onConfigure → first DB
 *   open aborted → HomeActivity's fallback text.
 *
 *   JDBC tolerates the returned row, which is precisely why the JVM suite
 *   could never surface the bug. This test pins the behavior as living
 *   documentation; PlatformGuardTest prevents the call pattern from returning.
 */
public class PragmaRowBehaviorTest {

    @BeforeClass public static void loadDriver() throws Exception {
        Class.forName("org.sqlite.JDBC");
    }

    @Test public void journalModeAssignmentReturnsARow() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA journal_mode=WAL")) {
            assertTrue("journal_mode assignment must yield a row (this is what Android's execSQL rejects)",
                    rs.next());
            String mode = rs.getString(1).toLowerCase();
            // In-memory DBs cannot use WAL and report "memory"; file DBs report "wal".
            // Either way the assignment RETURNED A ROW — the documented Android execSQL trap.
            assertTrue("unexpected journal mode: " + mode,
                    mode.equals("memory") || mode.equals("wal"));
        }
    }

    @Test public void foreignKeysQueryReturnsARowButSetViaApiDoesNotMatter() throws Exception {
        try (Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement st = c.createStatement()) {
            st.execute("PRAGMA foreign_keys=ON");
            try (ResultSet rs = st.executeQuery("PRAGMA foreign_keys")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
        }
    }
}
