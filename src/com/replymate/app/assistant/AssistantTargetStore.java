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
        public String cachedPiB64 = "";       // P-background-7: cached reply PendingIntent
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
        m.put("cachedPi", "");            // geometry changed → drop any stale cached target
        m.put("capturedAt", Long.valueOf(nowMs));
        kv.put(AssistantPlanner.targetKvKey(contactId), Json.write(m));
    }

    /** P-background-7 (approve AFTER dismissal): resolve the stored target's reply
     *  action on the LIVE notification and cache its PendingIntent (parcel → base64
     *  in kv). PendingIntents are system tokens that survive our process death and
     *  often the notification itself; the approve path sends through this cache when
     *  the original notification is gone. Best-effort: any failure just leaves the
     *  cache empty and approval falls back honestly. */
    public static void cachePendingIntent(
            KvStore kv, long contactId,
            android.service.notification.StatusBarNotification sbn) {
        if (kv == null || sbn == null) return;
        Target t = load(kv, contactId);
        if (!t.usable()) return;
        android.app.Notification.Action a = ReplyActionResolver.select(
            sbn.getNotification(), t.source, t.resultKey, t.actionIndex);
        if (a == null || a.actionIntent == null) return;
        android.os.Parcel p = android.os.Parcel.obtain();
        try {
            android.app.PendingIntent.writePendingIntentOrNullToParcel(a.actionIntent, p);
            byte[] bytes = p.marshall();
            java.util.Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("pkg", t.packageName);
            m.put("sbnKey", t.sbnKey);
            m.put("actionIndex", Long.valueOf(t.actionIndex));
            m.put("source", Long.valueOf(t.source));
            m.put("resultKey", t.resultKey);
            m.put("probe", t.probe);
            m.put("cachedPi", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP));
            m.put("capturedAt", Long.valueOf(t.capturedAtMs));
            kv.put(AssistantPlanner.targetKvKey(contactId), Json.write(m));
        } catch (RuntimeException ignored) {
            // cache is an opportunistic fallback — absence is handled honestly
        } finally {
            p.recycle();
        }
    }

    /** Decode the cached reply PendingIntent (null when none/corrupt/expired bytes). */
    public static android.app.PendingIntent readCachedPi(Target t) {
        if (t == null || t.cachedPiB64 == null || t.cachedPiB64.isEmpty()) return null;
        android.os.Parcel p = android.os.Parcel.obtain();
        try {
            byte[] bytes = android.util.Base64.decode(t.cachedPiB64, android.util.Base64.NO_WRAP);
            p.unmarshall(bytes, 0, bytes.length);
            p.setDataPosition(0);
            return android.app.PendingIntent.readPendingIntentOrNullFromParcel(p);
        } catch (RuntimeException e) {
            return null;
        } finally {
            p.recycle();
        }
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

    /** P-background-6 (first-draft Approve): when capture-time raw carried no usable
     *  reply action, re-scan THIS APP's live notifications once at generate time and
     *  adopt the first one that really exposes RemoteInput (target + probe updated
     *  in place). Returns true when the target became usable. */
    public static boolean refreshFromLive(
            KvStore kv, long contactId, String packageName,
            android.service.notification.StatusBarNotification[] actives) {
        if (kv == null || packageName == null || packageName.isEmpty() || actives == null) {
            return false;
        }
        for (android.service.notification.StatusBarNotification sbn : actives) {
            if (sbn == null || !packageName.equals(sbn.getPackageName())) continue;
            RawNotif raw;
            try {
                raw = com.replymate.app.listener.NotifExtractor.toRaw(sbn);
            } catch (RuntimeException e) {
                continue;
            }
            if (raw == null) continue;
            if (AssistantPlanner.directAction(raw.actions) != null) {
                save(kv, contactId, raw, System.currentTimeMillis());
                cachePendingIntent(kv, contactId, sbn);   // P-background-7
                return true;
            }
        }
        return false;
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
            t.cachedPiB64 = o.str("cachedPi", "");
            t.capturedAtMs = o.lng("capturedAt", 0L);
        } catch (RuntimeException ignored) {
            // corrupt/absent → unusable target (callers fall back honestly)
        }
        return t;
    }
}
