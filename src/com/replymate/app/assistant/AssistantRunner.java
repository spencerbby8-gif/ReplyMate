package com.replymate.app.assistant;

import android.os.Handler;
import android.os.Looper;
import com.replymate.app.di.AppContainer;
import com.replymate.app.listener.ListenerStatus;
import com.replymate.app.platform.Tasks;
import com.replymate.core.assistant.AssistantEvent;
import com.replymate.core.assistant.AssistantPlanner;
import com.replymate.core.assistant.JobCoalescer;
import com.replymate.core.listener.BatchWindow;
import com.replymate.core.listener.IngestReport;
import com.replymate.core.listener.WatchedApps;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.model.Message;
import com.replymate.core.usecase.DraftOutcome;
import com.replymate.core.usecase.DraftService;
import com.replymate.core.util.Result;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** P-background-2 (fixed): debounced, coalesced BACKGROUND generation.
 *
 *  ROOT-CAUSE FIX (real-device failure in 1.4.0): the 1.4.0 runner executed the
 *  scheduled work on a main-looper Handler — i.e. ON THE UI THREAD — where the
 *  provider's blocking HttpURLConnection throws NetworkOnMainThreadException,
 *  which was swallowed into a log line. Every background generation died there.
 *  The main Handler is now ONLY a delay timer: the fire callback hops to
 *  Tasks.bg before any work.
 *
 *  Reliability pinning (owner's P-background-2 mandates):
 *   - ONE job per conversation: JobCoalescer supersedes tokens; an obsolete
 *     runnable is a no-op, and an in-flight older job re-checks its token BEFORE
 *     the provider call and aborts instead of burning a request.
 *   - Only the NEWEST message is used (state read at fire time, not schedule).
 *   - Never regenerate stale drafts: content-hash dedupe persisted in kv.
 *   - Never duplicate alerts: AssistantNotifier posts under a stable per-contact tag.
 *   - Never sends by itself: the only send path is a human Approve tap.
 *   - Every silent failure records a structured AssistantEvent (see AssistantDiag). */
public final class AssistantRunner {

    public static final String KV_ENABLED = "assistant.enabled";

    /** P-background-6: extra settle time ON TOP of the shared 5s batch window —
     *  a rapid burst coalesces into ONE scheduled job (rolling re-arm on every new
     *  ping), keeping "collect for a short window after the last message" true. */
    private static final long SETTLE_EXTRA_MS = 1500;

    /** Delay timer ONLY — work never runs on this thread. */
    private static final Handler TIMER = new Handler(Looper.getMainLooper());
    private static final Map<Long, Runnable> PENDING = new HashMap<Long, Runnable>();
    private static final JobCoalescer JOBS = new JobCoalescer();

    private AssistantRunner() {
    }

    public static boolean enabled(AppContainer c) {
        return "1".equals(c.kv().get(KV_ENABLED, "1"));
    }

    /** Debounced schedule alongside the message ping (burst ⇒ one run per contact).
     *  Every new schedule CANCELS the older one (same key ⇒ superseded token). */
    public static void schedule(final AppContainer c, final IngestReport.PingRequest ping) {
        if (ping == null) return;
        final long contactId = ping.contactId;
        final long token = JOBS.begin(contactId);

        Runnable previous;
        synchronized (PENDING) {
            previous = PENDING.remove(contactId);
        }
        if (previous != null) TIMER.removeCallbacks(previous);

        Runnable fire = new Runnable() {
            @Override public void run() {
                synchronized (PENDING) {
                    PENDING.remove(contactId);
                }
                // RC1 FIX: leave the timer thread BEFORE touching the pipeline.
                Tasks.bg(new Runnable() {
                    @Override public void run() {
                        if (!JOBS.isCurrent(contactId, token)) return;   // superseded
                        try {
                            generateAndNotify(c, contactId, ping.displayName, false, token);
                        } finally {
                            JOBS.finish(contactId, token);
                        }
                    }
                });
            }
        };
        synchronized (PENDING) {
            PENDING.put(contactId, fire);
        }
        long due = BatchWindow.dueAt(ping.latestTs);
        long delay = BatchWindow.delayFrom(System.currentTimeMillis(), due) + SETTLE_EXTRA_MS;
        TIMER.postDelayed(fire, delay);
    }

    /** The Regenerate button: force a fresh draft and REPLACE the alert in place.
     *  Supersedes any pending auto-job for the same conversation. */
    public static void regenerateNow(AppContainer c, long contactId, String name) {
        final long token = JOBS.begin(contactId);
        final long cid = contactId;
        Tasks.bg(new Runnable() {
            @Override public void run() {
                if (!JOBS.isCurrent(cid, token)) return;
                try {
                    generateAndNotify(c, cid, name, true, token);
                } finally {
                    JOBS.finish(cid, token);
                }
            }
        });
    }

    /** Core flow — background thread only. Never throws. */
    static void generateAndNotify(AppContainer c, long contactId, String name,
                                  boolean force, long token) {
        String who = name == null || name.trim().isEmpty() ? "#" + contactId : name;
        String tag = AssistantPlanner.notifTag(contactId);
        try {
            if (!enabled(c) && !force) return;
            if (!ListenerStatus.canPostNotifications(c.app())) {
                AssistantDiag.record(c, contactId, who, tag, "",
                    AssistantEvent.Stage.GATES,
                    "notification permission not granted",
                    "skipped generation (no way to show the result honestly)",
                    "allow ReplyMate notifications (Settings → ReplyMate notifications)");
                return;
            }

            // State is read AT FIRE TIME — a newer message during the debounce is
            // always the one answered, never the stale one.
            List<Message> recent = c.messages().lastMessages(contactId, 30);
            Message lastIncoming = null;
            for (Message m : recent) {
                if (m.direction == Direction.INCOMING) lastIncoming = m;
            }
            if (lastIncoming == null) return;

            String incomingHash = AssistantPlanner.hashOf(
                lastIncoming.body + "|" + lastIncoming.sentAt + "|" + lastIncoming.id);
            String doneHash = c.kv().get(AssistantPlanner.hashKvKey(contactId), "");
            if (!AssistantPlanner.shouldGenerate(incomingHash, doneHash, force)) return;

            // Last obsolete-job gate BEFORE the paid provider call: if a newer job
            // superseded this one while we were reading state, stop here.
            if (!JOBS.isCurrent(contactId, token)) {
                AssistantDiag.record(c, contactId, who, tag, "",
                    AssistantEvent.Stage.SCHEDULE,
                    "superseded by a newer job for this conversation",
                    "aborted before the provider call",
                    "");
                return;
            }

            DraftService svc = c.draftService();
            Result<DraftOutcome> r = svc.generateForContact(contactId);
            if (r == null || !r.ok || r.value == null || r.value.drafts.isEmpty()) {
                String reason = safe(r == null ? "no result" : r.error, 90);
                AssistantDiag.record(c, contactId, who, tag, "",
                    AssistantEvent.Stage.GENERATE, reason,
                    "nothing generated, no alert posted", fixFor(reason));
                return;
            }
            c.kv().put(AssistantPlanner.hashKvKey(contactId), incomingHash);

            Draft d = r.value.drafts.get(0);
            String appLabel = WatchedApps.labelFor(lastIncoming.channel);
            AssistantTargetStore.Target t = AssistantTargetStore.load(c.kv(), contactId);
            if (!t.usable()) {
                // P-background-6: the FIRST draft must show Approve & send whenever the
                // app really exposes RemoteInput — Regenerate must never be the way to
                // reveal it. The capture-time raw can predate the visible actions
                // (WhatsApp often posts first, attaches actions a beat later), so
                // re-probe the live shade once at generate time.
                com.replymate.app.listener.RmNotificationListener l =
                    com.replymate.app.listener.RmNotificationListener.active();
                if (l != null && AssistantTargetStore.refreshFromLive(
                        c.kv(), contactId, t.packageName, l.safeActiveNotifications())) {
                    t = AssistantTargetStore.load(c.kv(), contactId);
                    AssistantDiag.record(c, contactId, who, tag, t.sbnKey,
                        AssistantEvent.Stage.NOTIFY,
                        "capture-time raw carried no usable reply action",
                        "live re-probe found the app's real reply action — Approve & send is on the first draft",
                        "");
                }
            }
            AssistantPlanner.Capability cap = t.usable()
                ? AssistantPlanner.Capability.DIRECT : AssistantPlanner.Capability.NONE;
            if (cap == AssistantPlanner.Capability.NONE) {
                AssistantDiag.record(c, contactId, who, tag, t.sbnKey,
                    AssistantEvent.Stage.NOTIFY,
                    "no usable quick-reply — observed: " + safe(t.probe, 130),
                    "posted alert with honest Copy/Regenerate/Open fallback",
                    "if the app DOES show Reply, tell support: its action hides behind a surface we don't read yet");
            }

            AssistantNotifier.ensureChannels(c.app());
            AssistantNotifier.post(c.app(), contactId, who, appLabel, d.replyText, d.id, cap);
        } catch (RuntimeException e) {
            AssistantDiag.record(c, contactId, who, tag, "",
                AssistantEvent.Stage.GENERATE,
                e.getClass().getSimpleName() + ": " + safe(e.getMessage(), 70),
                "pipeline aborted safely", "re-open Settings → Diagnostics if it repeats");
        }
    }

    /** Map known honest gate errors to a human next-step. Unknowns stay generic. */
    private static String fixFor(String reason) {
        String r = reason == null ? "" : reason.toLowerCase(java.util.Locale.US);
        if (r.contains("private")) {
            return "expected — private contacts never use AI";
        }
        if (r.contains("ai replies are disabled")) {
            return "enable AI replies for this contact (contact → edit)";
        }
        if (r.contains("provider")) {
            return "check Settings → AI providers (key/model/state)";
        }
        if (r.contains("readable text") || r.contains("won't invent")
                || r.contains("no readable")) {
            return "expected — can't answer media/empty messages; open the app to read it";
        }
        if (r.contains("network") || r.contains("timeout") || r.contains("unable to resolve")) {
            return "network was down — it retries on the next message";
        }
        return "";
    }

    private static String safe(String s, int max) {
        if (s == null) return "?";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Test/diagnostic seam: how many coalesced jobs are currently tracked. */
    static int pendingJobs() {
        return JOBS.pendingCount();
    }
}
