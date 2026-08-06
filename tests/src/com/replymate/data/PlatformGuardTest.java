package com.replymate.data;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Regression guard for the 0.0.2 hotfix: statically scans the platform DB
 * configuration source and fails if the crash pattern (row-returning PRAGMA
 * through execSQL) ever comes back. Runs on the JVM because it only reads
 * source text — no platform APIs involved.
 */
public class PlatformGuardTest {

    private static String readSource(String relative) throws Exception {
        String root = System.getProperty("replymate.src", "/home/user/ReplyMate/src");
        File f = new File(root, relative);
        assertTrue("missing source file: " + f.getAbsolutePath(), f.isFile());
        byte[] bytes = Files.readAllBytes(f.toPath());
        return new String(bytes, Charset.forName("UTF-8"));
    }

    /** Strips block and line comments so guards scan CODE only — documentation
     *  is allowed to quote the historical crash string without tripping the guard.
     *  (Assumption: none of these files contain "//"+ inside string literals.) */
    private static String codeOnly(String src) {
        String noBlock = src.replaceAll("(?s)/\\*.*?\\*/", "");
        StringBuilder sb = new StringBuilder();
        for (String line : noBlock.split("\n")) {
            int i = line.indexOf("//");
            sb.append(i >= 0 ? line.substring(0, i) : line).append('\n');
        }
        return sb.toString();
    }

    @Test public void pragmasNeverUsesExecSqlForRowReturningPragmas() throws Exception {
        String src = codeOnly(readSource("com/replymate/data/db/Pragmas.java"));
        assertFalse("crash pattern returned: execSQL(PRAGMA journal_mode…",
                src.contains("execSQL(\"PRAGMA journal_mode"));
        assertFalse("risky pattern: execSQL(PRAGMA synchronous…",
                src.contains("execSQL(\"PRAGMA synchronous"));
        assertTrue("WAL must be enabled via the framework API",
                src.contains("enableWriteAheadLogging"));
        assertTrue("foreign keys must use the framework API",
                src.contains("setForeignKeyConstraintsEnabled"));
    }

    @Test public void noExecSqlOfPragmaAnywhereInDataLayer() throws Exception {
        String[] files = {
            "com/replymate/data/db/Pragmas.java",
            "com/replymate/data/db/DbHelper.java",
            "com/replymate/data/db/DbHealth.java"
        };
        for (String rel : files) {
            String src = codeOnly(readSource(rel));
            assertFalse(rel + " must not execSQL any PRAGMA (use rawQuery path)",
                    src.contains("execSQL(\"PRAGMA"));
        }
    }
}
