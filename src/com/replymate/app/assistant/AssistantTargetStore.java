package com.replymate.app.assistant;

import com.replymate.core.assistant.AssistantPlanner;
import com.replymate.core.json.Json;
import com.replymate.core.json.JsonObj;
import com.replymate.core.listener.RawNotif;
import com.replymate.core.ports.KvStore;
import java.util.LinkedHashMap;
import java.util.Map;

/** P-background: captures WHERE a conversation's quick-reply action lives
 *  (package + live sbn key + action index + RemoteInput result key) so the
 *  approve-tap can resolve it later. The PendingIntent itself is NEVER stored —
 *  it is re-read from the live StatusBarNotification at send time, so process
 *  death and notification updates can't hand us a stale intent. */
public final class AssistantTargetStore {

    private AssistantTargetStore() {
    }

    public static final class Target {
        public String packageName = "";
        public String sbnKey = "";
        public int actionIndex = -1;          // -1 ⇒ no direct-reply target
        public String resultKey = "";
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
        int idx = AssistantPlanner.directActionIndex(raw.actions);
        m.put("actionIndex", Long.valueOf(idx));
        String resultKey = "";
        if (idx >= 0) {
            for (RawNotif.ActionRef a : raw.actions) {
                if (a != null && a.index == idx) { resultKey = a.resultKey; break; }
            }
        }
        m.put("resultKey", resultKey);
        m.put("capturedAt", Long.valueOf(nowMs));
        kv.put(AssistantPlanner.targetKvKey(contactId), Json.write(m));
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
            t.resultKey = o.str("resultKey", "");
            t.capturedAtMs = o.lng("capturedAt", 0L);
        } catch (RuntimeException ignored) {
            // corrupt/absent → unusable target (callers fall back honestly)
        }
        return t;
    }
}
