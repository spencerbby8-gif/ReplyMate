package com.replymate.app.assistant;

import com.replymate.core.assistant.AssistantPlanner;
import com.replymate.core.json.Json;
import com.replymate.core.json.JsonObj;
import com.replymate.core.listener.RawNotif;
import com.replymate.core.ports.KvStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** P-background (hardened in P-background-3): captures WHERE a conversation's
 *  quick-reply action lives — WHICH documented list (standard vs wearable), the
 *  index inside that list, the RemoteInput result key, and the live sbn key — so
 *  the approve-tap can resolve it later. The PendingIntent is ALSO cached as a
 *  system token (P-background-7) so approval survives notification dismissal.
 *
 *  P-background-8: the target now also carries the conversation IDENTITY
 *  (conversationId / conversationTitle / title). After a dismissal, the source
 *  app may re-post the same chat under a NEW sbn key; findConversationMatch
 *  re-attaches approval to the FRESH notification via strict identity equality
 *  before ever touching the cached PendingIntent.
 *
 *  Also records a compact PROBE string of every action observed (title + remote
 *  input shape, both lists) — when capability is NONE the ledger shows exactly
 *  what the source app exposed instead of a bare "no usable action". */
public final class AssistantTargetStore {

    private AssistantTargetStore() {
    }

    /** P-intelligence-4: the LIVE reply PendingIntent, keyed by contact, kept for the
     *  lifetime of THIS process in parallel with the persisted byte cache. Research
     *  (official PendingIntent reference + Parcel.marshall() contract, verified
     *  2026-08-08): a PendingIntent is a system token that survives the CREATOR's
     *  death, and notification dismissal does NOT cancel it — but a MARSHALLED binder
     *  handle is only meaningful while the writing process lives (the byte cache is
     *  best-effort). Keeping the actual object strongly referenced means the common
     *  case — dismiss the source alert, approve later in the same session — fires a
     *  guaranteed-valid binder instead of an unmarshalled copy. Never sent to disk. */
    private static final ConcurrentHashMap<Long, android.app.PendingIntent> LIVE_PI =
        new ConcurrentHashMap<Long, android.app.PendingIntent>();

    /** The cached reply PI and WHERE it came from (ledger honesty). */
    public static final class CachedPi {
        public final android.app.PendingIntent pi;
        /** true = the live in-process token (strongest), false = persisted bytes
         *  (best-effort after a process restart; with the process-start marker the
         *  ledger says when the bytes predate a restart). */
        public final boolean inMemory;
        CachedPi(android.app.PendingIntent pi, boolean inMemory) {
            this.pi = pi;
            this.inMemory = inMemory;
        }
    }

    public static final class Target {
        public long contactId = -1;
        public String packageName = "";
        public String sbnKey = "";
        public int actionIndex = -1;          // -1 ⇒ no direct-reply target
        public int source = RawNotif.ActionRef.SRC_STANDARD;
        public String resultKey = "";
        public String probe = "";             // observed geometry (diagnostics)
        public String cachedPiB64 = "";       // P-background-7: cached reply PendingIntent
        public long capturedAtMs;
        /** P-intelligence-4: our process' start marker when the byte cache was
         *  written (android.os.Process.getStartElapsedRealtime, API 24 = minSdk).
         *  Mismatch at fire time ⇒ ReplyMate restarted since capture ⇒ the stored
         *  bytes predate the restart (ledger wording only — still attempted). */
        public long procAtCache = 0L;
        /* P-background-8 identity fields (any may be "" when the app doesn't say): */
        public String conversationId = "";
        public String convTitle = "";
        public String title = "";

        public boolean usable() {
            return actionIndex >= 0 && !sbnKey.isEmpty() && !resultKey.isEmpty();
        }

        public boolean identifiable() {
            return com.replymate.core.listener.ConversationMatch.identifiable(
                conversationId, convTitle, title);
        }
    }

    /** Save the best reply target found on this raw notification for this contact. */
    public static void save(KvStore kv, long contactId, RawNotif raw, long nowMs) {
        if (kv == null || raw == null) return;
        Target t = new Target();
        t.contactId = contactId;
        t.packageName = raw.packageName == null ? "" : raw.packageName;
        t.sbnKey = raw.sbnKey == null ? "" : raw.sbnKey;
        RawNotif.ActionRef best = AssistantPlanner.directAction(raw.actions);
        t.actionIndex = best == null ? -1 : best.index;
        t.source = best == null ? RawNotif.ActionRef.SRC_STANDARD : best.source;
        t.resultKey = best == null || best.resultKey == null ? "" : best.resultKey;
        t.probe = probeOf(raw.actions);
        t.cachedPiB64 = "";            // geometry changed → drop any stale cached target
        t.procAtCache = 0L;
        LIVE_PI.remove(Long.valueOf(contactId));   // stale live token must never outlive new geometry
        t.capturedAtMs = nowMs;
        t.conversationId = raw.conversationId == null ? "" : raw.conversationId;
        t.convTitle = raw.convTitle == null ? "" : raw.convTitle;
        t.title = raw.title == null ? "" : raw.title;
        kv.put(AssistantPlanner.targetKvKey(contactId), Json.write(toMap(t)));
    }

    /** Single source of truth for the persisted shape — save() and
     *  cachePendingIntent() MUST never drift (they did before this helper existed). */
    private static Map<String, Object> toMap(Target t) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("pkg", t.packageName);
        m.put("sbnKey", t.sbnKey);
        m.put("actionIndex", Long.valueOf(t.actionIndex));
        m.put("source", Long.valueOf(t.source));
        m.put("resultKey", t.resultKey);
        m.put("probe", t.probe);
        m.put("cachedPi", t.cachedPiB64 == null ? "" : t.cachedPiB64);
        m.put("procAtCache", Long.valueOf(t.procAtCache));
        m.put("capturedAt", Long.valueOf(t.capturedAtMs));
        m.put("convId", t.conversationId == null ? "" : t.conversationId);
        m.put("convTitle", t.convTitle == null ? "" : t.convTitle);
        m.put("title", t.title == null ? "" : t.title);
        return m;
    }

    /** P-background-7 (approve AFTER dismissal), P-intelligence-4 hardened: resolve
     *  the stored target's reply action on the LIVE notification and cache its
     *  PendingIntent TWO ways — (1) the live object in {@link #LIVE_PI} for this
     *  process' lifetime (the reliable same-session flavor), and (2) parcel → base64
     *  in kv as best-effort persistence. Officially a PendingIntent token survives
     *  the CREATOR's death and a plain notification dismissal never cancels it
     *  (only the app itself can) — but MARSHALLED binder handles are not
     *  persistence-safe, so the byte flavor is attempted honestly and any failure
     *  falls back with a real reason. */
    public static void cachePendingIntent(
            KvStore kv, long contactId,
            android.service.notification.StatusBarNotification sbn) {
        if (kv == null || sbn == null) return;
        Target t = load(kv, contactId);
        if (!t.usable()) return;
        android.app.Notification.Action a = ReplyActionResolver.selectAnySurface(
            sbn.getNotification(), t.source, t.resultKey, t.actionIndex);
        if (a == null || a.actionIntent == null) return;
        // P-intelligence-4: keep the live token for this process' lifetime — the
        // dismissal-surviving flavor that doesn't depend on parcelled binder handles.
        LIVE_PI.put(Long.valueOf(contactId), a.actionIntent);
        t.procAtCache = android.os.Process.getStartElapsedRealtime();
        android.os.Parcel p = android.os.Parcel.obtain();
        try {
            android.app.PendingIntent.writePendingIntentOrNullToParcel(a.actionIntent, p);
            byte[] bytes = p.marshall();
            t.cachedPiB64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP);
            kv.put(AssistantPlanner.targetKvKey(contactId), Json.write(toMap(t)));
        } catch (RuntimeException ignored) {
            // cache is an opportunistic fallback — absence is handled honestly
        } finally {
            p.recycle();
        }
    }

    /** The cached reply PendingIntent for this target, or null. The LIVE in-process
     *  token wins (always a valid binder here); the persisted bytes are the
     *  cross-process best-effort. Caller reads {@link CachedPi#inMemory} for ledger
     *  honesty about which flavor fired. */
    public static CachedPi readCachedPi(Target t) {
        if (t == null) return null;
        android.app.PendingIntent live = LIVE_PI.get(Long.valueOf(t.contactId));
        if (live != null) return new CachedPi(live, true);
        if (t.cachedPiB64 == null || t.cachedPiB64.isEmpty()) return null;
        android.os.Parcel p = android.os.Parcel.obtain();
        try {
            byte[] bytes = android.util.Base64.decode(t.cachedPiB64, android.util.Base64.NO_WRAP);
            p.unmarshall(bytes, 0, bytes.length);
            p.setDataPosition(0);
            android.app.PendingIntent pi =
                android.app.PendingIntent.readPendingIntentOrNullFromParcel(p);
            return pi == null ? null : new CachedPi(pi, false);
        } catch (RuntimeException e) {
            return null;
        } finally {
            p.recycle();
        }
    }

    /** True when this process started after the byte cache was written — the ledger's
     *  "the stored copy predates a ReplyMate restart" marker. Never used to gate. */
    public static boolean restartedSinceCache(Target t) {
        return t != null && t.procAtCache > 0L
            && t.procAtCache != android.os.Process.getStartElapsedRealtime();
    }

    /** Test hook (Robolectric): simulate a process restart by dropping live tokens. */
    public static void clearLivePiForTests() {
        LIVE_PI.clear();
    }

    /** P-background-8: find a LIVE notification from the same app that the stored
     *  identity fields say is THE SAME conversation (posted under a new key after
     *  the original was dismissed). Strict official-fields match only
     *  (conversationId > conversationTitle > title) — never a fuzzy guess.
     *  Returns the most recent match, or null (honest: no live same-chat alert). */
    public static android.service.notification.StatusBarNotification findConversationMatch(
            Target t, android.service.notification.StatusBarNotification[] actives) {
        if (t == null || actives == null || !t.identifiable()
                || t.packageName == null || t.packageName.isEmpty()) return null;
        android.service.notification.StatusBarNotification best = null;
        for (android.service.notification.StatusBarNotification sbn : actives) {
            if (sbn == null || !t.packageName.equals(sbn.getPackageName())) continue;
            RawNotif live;
            try {
                live = com.replymate.app.listener.NotifExtractor.toRaw(sbn);
            } catch (RuntimeException e) {
                continue;
            }
            if (live == null) continue;
            if (!com.replymate.core.listener.ConversationMatch.same(
                    t.packageName, t.conversationId, t.convTitle, t.title,
                    live.packageName, live.conversationId, live.convTitle, live.title)) continue;
            if (best == null || sbn.getPostTime() >= best.getPostTime()) best = sbn;
        }
        return best;
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
        t.contactId = contactId;
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
            t.procAtCache = o.lng("procAtCache", 0L);
            t.capturedAtMs = o.lng("capturedAt", 0L);
            t.conversationId = o.str("convId", "");
            t.convTitle = o.str("convTitle", "");
            t.title = o.str("title", "");
        } catch (RuntimeException ignored) {
            // corrupt/absent → unusable target (callers fall back honestly)
        }
        return t;
    }
}
