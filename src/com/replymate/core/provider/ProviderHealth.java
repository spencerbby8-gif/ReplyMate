package com.replymate.core.provider;

/** P21-production §7: provider health marks are MODEL-SCOPED. A "tested ✓" claim
 *  made for model A must never re-render as proof that model B works (the owner's
 *  Nov-4 defect: a backend-level "model not found" 404 was masked because an old
 *  generic probe had stamped the key healthy). The kv value is self-describing:
 *      ok|<model>|<millis>      fail|<model>|<millis>
 *  Legacy values "ok|<millis>"/"fail|<millis>" (no model recorded) decode with
 *  model "" and must be presented as untrustworthy ("model not recorded").
 *  Secrets never appear here — model ids and timestamps only. */
public final class ProviderHealth {

    public static final String KEY_PREFIX = "provider.health.";

    private ProviderHealth() { }

    public static String keyFor(String wire) { return KEY_PREFIX + wire; }

    /** Decoded health value. `ok` = the probe succeeded; `model` may be "" for
     *  legacy marks written before health was model-scoped (or a provider with no
     *  model field); `millis` = when the probe ran (0 when unparseable). */
    public static final class Mark {
        public final boolean ok;
        public final String model;
        public final long millis;
        Mark(boolean ok, String model, long millis) {
            this.ok = ok; this.model = model; this.millis = millis;
        }
    }

    public static String encode(boolean ok, String model, long millis) {
        return (ok ? "ok" : "fail") + "|" + (model == null ? "" : model.trim()) + "|" + millis;
    }

    /** Null/empty → null. Malformed timestamps → millis 0 (never throws). */
    public static Mark decode(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String[] p = raw.split("\\|", -1);
        if (p.length == 2) {           // legacy: state|<millis>
            return new Mark("ok".equals(p[0]), "", parseMs(p[1]));
        }
        if (p.length >= 3) {
            return new Mark("ok".equals(p[0]), p[1], parseMs(p[2]));
        }
        return null;
    }

    private static long parseMs(String s) {
        try { return Long.parseLong(s.trim()); } catch (NumberFormatException e) { return 0; }
    }
}
