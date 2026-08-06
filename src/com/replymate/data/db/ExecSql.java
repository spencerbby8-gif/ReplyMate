package com.replymate.data.db;

/** Minimal SQL execution surface the migration runner needs. Implemented by
 *  DbHelper (device) and by a JDBC adapter (unit tests). Deliberately free of
 *  any platform types so migrations are testable on the JVM. */
public interface ExecSql {
    void exec(String sql);
    int getUserVersion();
    void setUserVersion(int version);
}
