package com.replymate.core.listener;

import com.replymate.core.ports.KvStore;
import com.replymate.core.util.Clock;

/** P-listener-foundation §3: capture-boundary trace. For every notification the
 *  listener touches, record WHERE it went, stage by stage, so a real device can
 *  pinpoint exactly where a message disappears:
 *
 *      callback→lane → extract → route → events → ingest(stored/dupes/filtered)
 *      → pings → scheduled
 *
 *  Privacy-safe by construction: NO message bodies, NO titles, NO contact or
 *  sender names, NO PendingIntent contents, NO API keys (DiagnosticsRing.redact
 *  stays the durable-storage choke point regardless). Only: package name,
 *  hashed sbn-key tag, bound flag, outcome kind, category names, presence
 *  booleans and counts. The ring is bounded by DiagnosticsRing's cap — this is
 *  a diagnostic lens, not a log. */
public final class ListenerTrace {

    public static final String KV_TRACE = "listener.trace";
    private static final String KV_SHAPE_PREFIX = "listener.shape.last.";

    private ListenerTrace() { }

    /** Append one bounded trace line (newest first). */
    public static void line(KvStore kv, Clock clock, String line) {
        if (kv == null || clock == null || line == null) return;
        kv.put(KV_TRACE, DiagnosticsRing.append(kv.get(KV_TRACE, ""), clock.now(), line));
    }

    /** Display lines (newest first), same "ts\tline" format as the diag ring. */
    public static java.util.List<String> lines(KvStore kv) {
        return DiagnosticsRing.lines(kv == null ? "" : kv.get(KV_TRACE, ""));
    }

    /** Short, stable, non-reversible tag for an sbn key — identifies re-posts of
     *  the SAME notification across trace lines without persisting the platform
     *  key. FNV-1a 32-bit, hex. Empty input → "-". */
    public static String keyTag(String sbnKey) {
        if (sbnKey == null || sbnKey.isEmpty()) return "-";
        long h = 0x811c9dc5L;
        for (int i = 0; i < sbnKey.length(); i++) {
            h ^= sbnKey.charAt(i) & 0xffff;
            h = (h * 0x01000193L) & 0xffffffffL;
        }
        return Long.toHexString(h);
    }

    /** Structural signature of the raw notification — presence booleans and
     *  counts ONLY, never content. Two posts with identical structure share one
     *  signature; a late-added Reply action, MessagingStyle history appearing on
     *  re-post, or a growing burst CHANGES it — and that churn is itself the
     *  device evidence (late reply action, history re-post, SBN-key drift). */
    public static String shapeSignature(RawNotif raw) {
        if (raw == null) return "null";
        int named = 0;
        for (RawNotif.Entry m : raw.messages) {
            if (SystemLines.hasSenderIdentity(m.senderName, m.senderKey, m.senderUri)) named++;
        }
        int std = 0, wear = 0, freeForm = 0;
        for (RawNotif.ActionRef a : raw.actions) {
            if (a == null) continue;
            if (a.source == RawNotif.ActionRef.SRC_WEARABLE) wear++; else std++;
            if (a.remoteFreeForm) freeForm++;
        }
        return "cat=" + (raw.category == null ? "-" : raw.category)
            + " msgs=" + raw.messages.size() + "/" + raw.historic.size()
            + " named=" + named
            + " act=" + std + "+" + wear + "/" + freeForm
            + " reply=" + (freeForm > 0 ? "Y" : "N")
            + " convId=" + yesNo(raw.conversationId)
            + " grp=" + (raw.group == null ? "?" : (raw.group.booleanValue() ? "1" : "0"))
            + " ttb=" + (raw.title != null ? 1 : 0) + (raw.text != null ? 1 : 0)
                + (raw.bigText != null ? 1 : 0)
            + " ct=" + (raw.convTitle != null ? 1 : 0)
            + " ong=" + (raw.ongoing ? 1 : 0)
            + " prog=" + (raw.progressMax > 0 ? 1 : 0);
    }

    /** Records one trace line ONLY when this package's shape signature differs
     *  from the last one seen for that package — normal steady traffic keeps the
     *  ring sparse; a shape flip (late Reply action, MessagingStyle appearing on
     *  re-post, history growth) leaves exactly one new line per change.
     *  Returns true when a line was recorded. */
    public static boolean maybeRecordShape(KvStore kv, Clock clock, RawNotif raw) {
        if (kv == null || raw == null) return false;
        String pkg = raw.packageName == null ? "?" : raw.packageName;
        String sig = shapeSignature(raw);
        String key = KV_SHAPE_PREFIX + pkg;
        if (sig.equals(kv.get(key, ""))) return false;
        kv.put(key, sig);
        line(kv, clock, "shape · " + pkg + " · k#" + keyTag(raw.sbnKey) + " · " + sig);
        return true;
    }

    private static String yesNo(String s) {
        return s != null && !s.isEmpty() ? "Y" : "N";
    }
}
