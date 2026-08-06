package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import com.replymate.core.ports.KvStore;

/** Per-app listener counters in app_kv (P3 diagnostics):
 *    listener.stats.<wire>.received | .parsed | .ignored | .failed
 *    listener.stats.<wire>.last_reason  (content-free short string)            */
public final class ListenerStats {

    private final KvStore kv;

    public ListenerStats(KvStore kv) { this.kv = kv; }

    public void received(Channel c) { bump(c, "received"); }
    public void parsed(Channel c)   { bump(c, "parsed"); }
    public void ignored(Channel c, String reason) { bump(c, "ignored"); reason(c, reason); }
    public void failed(Channel c, String reason)  { bump(c, "failed");  reason(c, reason); }

    public long receivedOf(Channel c) { return val(c, "received"); }
    public long parsedOf(Channel c)   { return val(c, "parsed"); }
    public long ignoredOf(Channel c)  { return val(c, "ignored"); }
    public long failedOf(Channel c)   { return val(c, "failed"); }
    public String lastReasonOf(Channel c) {
        return kv.get(key(c, "last_reason"), "");
    }

    private void bump(Channel c, String what) {
        kv.put(key(c, what), String.valueOf(val(c, what) + 1));
    }

    private void reason(Channel c, String reason) {
        if (reason != null && !reason.isEmpty()) {
            kv.put(key(c, "last_reason"), reason.length() > 60 ? reason.substring(0, 60) : reason);
        }
    }

    private long val(Channel c, String what) {
        try { return Long.parseLong(kv.get(key(c, what), "0")); }
        catch (NumberFormatException nfe) { return 0; }
    }

    private static String key(Channel c, String what) {
        return "listener.stats." + c.wire + "." + what;
    }
}
