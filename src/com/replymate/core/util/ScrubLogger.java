package com.replymate.core.util;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Logger decorator that removes registered sensitive fragments (API keys, PINs)
 *  from every message before forwarding. Registered fragments themselves are never logged. */
public final class ScrubLogger implements Logger {
    private final Logger delegate;
    private final List<String> sensitive = new CopyOnWriteArrayList<String>();

    public ScrubLogger(Logger delegate) {
        this.delegate = delegate;
    }

    public static ScrubLogger wrap(Logger delegate) {
        return new ScrubLogger(delegate);
    }

    /** Register a value that must never appear in logs (e.g. an API key). */
    public void registerSensitive(String fragment) {
        if (fragment != null && fragment.length() >= 6) sensitive.add(fragment);
    }

    private String scrub(String msg) {
        if (msg == null) return "(null)";
        String out = msg;
        for (String s : sensitive) out = out.replace(s, "***");
        return out;
    }

    @Override public void d(String tag, String msg) { delegate.d(tag, scrub(msg)); }
    @Override public void i(String tag, String msg) { delegate.i(tag, scrub(msg)); }
    @Override public void w(String tag, String msg) { delegate.w(tag, scrub(msg)); }
    @Override public void e(String tag, String msg) { delegate.e(tag, scrub(msg)); }
    @Override public void e(String tag, String msg, Throwable t) { delegate.e(tag, scrub(msg), t); }
}
