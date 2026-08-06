package com.replymate.core.util;

/** Logging port. Release builds must log only event names/ids — never secrets or message bodies. */
public interface Logger {
    void d(String tag, String msg);
    void i(String tag, String msg);
    void w(String tag, String msg);
    void e(String tag, String msg);
    void e(String tag, String msg, Throwable t);
}
