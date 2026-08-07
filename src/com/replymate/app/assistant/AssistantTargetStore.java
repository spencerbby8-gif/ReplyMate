package com.replymate.app.assistant;

import com.replymate.core.assistant.AssistantPlanner;
import com.replymate.core.json.Json;
import com.replymate.core.json.JsonObj;
import com.replymate.core.listener.RawNotif;
import com.replymate.core.ports.KvStore;
import java.util.LinkedHashMap;
import java.util.Map;

/** P-background (hardened in P-background-3): captures WHERE a conversation's
 *  quick-reply action lives — WHICH documented list (standard vs wearable), the
 *  index inside that list, the RemoteInput result key, and the live sbn key — so
 *  the approve-tap can resolve it later. The PendingIntent itself is NEVER stored;
 *  it is re-read from the live StatusBarNotification at send time.
 *
 *  Also records a compact PROBE string of every action observed (title + remote
 *  input shape, both lists) — when capability is NONE the ledger shows exactly
 *  what the source app exposed instead of a bare "no usable action". */
public final class AssistantTargetStore {

    private AssistantTargetStore() {
    }

    public static final class Target {
        public String packageName = "";
        public String sbnKey = "";
        public int actionIndex = -1;          // -1 ⇒ no direct-reply target
        public int source = RawNotif.ActionRef.SRC_STANDARD;
        public String resultKey = "";
        public String probe = "";             // observed geometry (diagnostics)
        public long capturedAtMs;

        public boolean usable() {
            return actionIndex >= 0 && !sbnKey.isEmpty() && !resultKey.isEmpty();
        }
    }

    /** Save the best reply target found on this raw notification for this contact. */
    public static void save(KvStore kv, long contactId, RawNotif raw, long nowMs) {
        if (kv == null || raw == null) return;
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("pkg", raw.packageName == null ? "" : raw.packageName);
        m.put("sbnKey", raw.sbnKey == null ? "" : raw.sbnKey);
        RawNotif.ActionRef best = AssistantPlanner.directAction(raw.actions);
        m.put("actionIndex", Long.valueOf(best == null ? -1 : best.index));
        m.put("source", Long.valueOf(best == null
            ? (long) RawNotif.ActionRef.SRC_STANDARD : (long) best.source));
        m.put("resultKey", best == null || best.resultKey == null ? "" : best.resultKey);
        m.put("probe", probeOf(raw.actions));
        m.put("capturedAt", Long.valueOf(nowMs));
        kv.put(AssistantPlanner.targetKvKey(contactId), Json.write(m));
    }

    /** Compact observed-geometry line, e.g.
     *  "std[Reply:ri0][Mark as read:ri0] wear[Reply:ri1ff/key_text_reply]" */
    public static String probeOf(java.util.List<RawNotif.ActionRef> actions) {
        StringBuilder std = new StringBuilder();
        StringBuilder wear = new StringBuilder();
        if (actions != null) {
            for (RawNotif.ActionRef a : actions) {
                if (a == null) continue;
                StringBuilder dst = a.source == RawNotif.ActionRef.SRC_WEARABLE ? wear : std;
                dst.append('[').append(a.title == null ? "?" : a.title).append(":ri");
                dst.append(a.remoteFreeForm ? "1ff" : "0");
                if (a.resultKey != null && !a.resultKey.isEmpty()) {
                    dst.append('/').append(a.resultKey);
                }
                dst.append(']');
            }
        }
        String s = "std" + (std.length() == 0 ? "[]" : std.toString())
            + " wear" + (wear.length() == 0 ? "[]" : wear.toString());
        return s.length() <= 180 ? s : s.substring(0, 180);
    }

    /** Load — tolerant: missing/corrupt json yields an unusable (NONE) target. */
    public static Target load(KvStore kv, long contactId) {
        Target t = new Target();
        if (kv == null) return t;
        try {
            JsonObj o = Json.parseObj(kv.get(AssistantPlanner.targetKvKey(contactId), ""));
            t.packageName = o.str("pkg", "");
            t.sbnKey = o.str("sbnKey", "");
            t.actionIndex = (int) o.lng("actionIndex", -1L);
            t.source = (int) o.lng("source", 0L);
            t.resultKey = o.str("resultKey", "");
            t.probe = o.str("probe", "");
            t.capturedAtMs = o.lng("capturedAt", 0L);
        } catch (RuntimeException ignored) {
            // corrupt/absent → unusable target (callers fall back honestly)
        }
        return t;
    }
}
