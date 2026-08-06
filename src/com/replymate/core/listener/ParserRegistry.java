package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Package → parser routing (the "provider-based parser" registry) with
 *  watch toggling applied BEFORE any processing, plus per-app stats recording.
 *  Outcome kinds make every drop reason explicit for diagnostics. */
public final class ParserRegistry {

    public enum OutcomeKind {
        NOT_WATCHED,        // package not in catalog — silent by design
        DISABLED,           // user toggle off for this channel
        PARSED,             // events produced
        IGNORED,            // parser said: unsupported/noise (safe skip)
        FAILED              // parser said: malformed input
    }

    public static final class Outcome {
        public final OutcomeKind kind;
        public final Channel channel;       // null for NOT_WATCHED
        public final java.util.List<NotifEvent> events;
        public final String reason;

        Outcome(OutcomeKind kind, Channel ch, java.util.List<NotifEvent> ev, String reason) {
            this.kind = kind;
            this.channel = ch;
            this.events = ev;
            this.reason = reason;
        }
    }

    private final Map<String, WatchedApps.AppDef> byPackage = new HashMap<String, WatchedApps.AppDef>();

    public ParserRegistry() {
        for (WatchedApps.AppDef def : WatchedApps.all()) {
            byPackage.put(def.packageName, def);
        }
    }

    public Channel channelForPackage(String pkg) {
        WatchedApps.AppDef def = byPackage.get(pkg);
        return def == null ? null : def.channel;
    }

    /** Parse with watch gating applied FIRST (nothing is processed for disabled apps). */
    public Outcome route(String pkg, RawNotif raw, Set<Channel> enabled, ListenerStats stats) {
        WatchedApps.AppDef def = byPackage.get(pkg);
        if (def == null) return new Outcome(OutcomeKind.NOT_WATCHED, null, null, null);

        Channel ch = def.channel;
        if (stats != null) stats.received(ch);

        if (enabled != null && !enabled.contains(ch)) {
            if (stats != null) stats.ignored(ch, "watch disabled for " + ch.wire);
            return new Outcome(OutcomeKind.DISABLED, ch, null, "watch disabled");
        }

        NotifParser.Result r;
        try {
            r = def.parser.parse(raw);
        } catch (RuntimeException boom) {
            if (stats != null) stats.failed(ch, "unwind: " + boom.getClass().getSimpleName());
            return new Outcome(OutcomeKind.FAILED, ch, null, boom.getClass().getSimpleName());
        }

        switch (r.kind) {
            case EVENTS:
                if (r.events == null || r.events.isEmpty()) {
                    if (stats != null) stats.ignored(ch, "parser produced nothing");
                    return new Outcome(OutcomeKind.IGNORED, ch, null, "parser produced nothing");
                }
                if (stats != null) stats.parsed(ch);
                return new Outcome(OutcomeKind.PARSED, ch, r.events, null);
            case IGNORE:
                if (stats != null) stats.ignored(ch, r.reason);
                return new Outcome(OutcomeKind.IGNORED, ch, null, r.reason);
            default:
                if (stats != null) stats.failed(ch, r.reason);
                return new Outcome(OutcomeKind.FAILED, ch, null, r.reason);
        }
    }

    /** Convenience: the default enabled set = every channel with watch.<wire> != "0". */
    public static Set<Channel> enabledFromKv(com.replymate.core.ports.KvStore kv,
                                             Set<Channel> defaultsOn) {
        Set<Channel> out = new HashSet<Channel>();
        for (Channel c : Channel.values()) {
            if (c == Channel.MANUAL) continue;
            boolean def = defaultsOn != null && defaultsOn.contains(c);
            String v = kv.get("watch." + c.wire, def ? "1" : "0");
            if ("1".equals(v)) out.add(c);
        }
        return out;
    }
}
