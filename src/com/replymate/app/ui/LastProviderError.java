package com.replymate.app.ui;

import com.replymate.app.di.AppContainer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Remembers the most recent provider failure (full diagnostics block) so
 *  Settings → Diagnostics can show exactly what the provider said, even after the
 *  screen that produced the error is long gone. Written from every failure surface
 *  (provider editor, voice previews, draft generation). */
public final class LastProviderError {

    public static final String KV_KEY = "provider.last.error";

    private LastProviderError() { }

    public static void save(AppContainer c, String errorBlock) {
        if (c == null || errorBlock == null || errorBlock.trim().isEmpty()) return;
        String v = errorBlock.length() > 1800 ? errorBlock.substring(0, 1800) + "…" : errorBlock;
        String when = new SimpleDateFormat("MMM d, HH:mm:ss", Locale.US).format(new Date());
        c.kv().put(KV_KEY, when + "\n" + v);
    }
}
