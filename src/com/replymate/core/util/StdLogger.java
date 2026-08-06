package com.replymate.core.util;

/** JVM stdout logger — used by unit tests and scripts. */
public final class StdLogger implements Logger {
    @Override public void d(String tag, String msg) { print("D", tag, msg); }
    @Override public void i(String tag, String msg) { print("I", tag, msg); }
    @Override public void w(String tag, String msg) { print("W", tag, msg); }
    @Override public void e(String tag, String msg) { print("E", tag, msg); }
    @Override public void e(String tag, String msg, Throwable t) {
        print("E", tag, msg);
        t.printStackTrace(System.out);
    }
    private void print(String level, String tag, String msg) {
        System.out.println(level + "/" + tag + ": " + msg);
    }
}
