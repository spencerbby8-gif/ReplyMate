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

    /** P-background-9: sweep throttles. Listener (re)connects can be frequent on
     *  some OEM builds, and connectivity callbacks fire per network flap — the
     *  catch-up sweep they trigger is idempotent but not free (one full contact
     *  scan). */
    public static final long CONNECT_SWEEP_MIN_INTERVAL_MS = 15000L;
    public static final long NETWORK_SWEEP_MIN_INTERVAL_MS = 45000L;

    /** P-background-9: last catch-up sweep timestamp (throttle anchor). */
    private static final String KV_LAST_SWEEP = "assistant.catchup.last_run";

    /** P-background-9: diag-dedupe marker for aged-out conversations —
     *  "…catchup.aged.<contactId>" holds the message id we already logged about,
     *  so a stale unanswered chat is explained ONCE, not on every sweep. */
    private static final String KV_AGED_PREFIX = "assistant.catchup.aged.";

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
                // P-background-9: the GENERATION lane — slow research/retries can
                // never sit on the capture lane's threads.
                Tasks.gen(new Runnable() {
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
        Tasks.gen(new Runnable() {
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

    /** P-intelligence-19 §2: IN-APP regeneration must update the shade alert too —
     *  the SAME stable tag+id, the fresh draft as primary content, no fresh pop
     *  (force), never a duplicate card. Re-posts through the exact notifyDraft
     *  path so capability buttons recompute from the live target and guard rails
     *  stay identical. No-op when there is nothing to point the alert at. */
    public static void refreshAlert(AppContainer c, long contactId, String name,
                                    Message lastIncoming, Draft d) {
        if (c == null || d == null || lastIncoming == null) return;
        notifyDraft(c, contactId,
            name == null ? "#" + contactId : name, lastIncoming, d, true);
    }

    /** P-intelligence-14: AUTO FOLLOW-UP trigger. Every approval path (copy /
     *  edited-copy / quick-reply send, in-app or from the shade) calls this. The
     *  per-contact switch and every "not now" case are JVM-pinned in
     *  {@code FollowUpPolicy} inside {@code DraftService.maybePrepareFollowUp};
     *  here we only wire GENERATION-lane execution, the honestly-labeled alert
     *  ("Follow-up idea", never "reply for") and the diag trail. The result is a
     *  GENERATED draft: approval stays with the owner — it NEVER sends itself. */
    public static void maybeFollowUp(final AppContainer c, final long contactId,
                                     final long approvedDraftId) {
        if (c == null || !enabled(c)) return;
        Tasks.gen(new Runnable() {
            @Override public void run() {
                String tag = AssistantPlanner.notifTag(contactId);
                try {
                    Draft approved = null;
                    for (Draft d : c.drafts().byContact(contactId, 10)) {
                        if (d != null && d.id == approvedDraftId) { approved = d; break; }
                    }
                    Result<DraftOutcome> r =
                        c.draftService().maybePrepareFollowUp(contactId, approved);
                    if (r == null || !r.ok || r.value == null || r.value.drafts.isEmpty()) {
                        String why = r == null ? "no result" : r.error;
                        // quiet policy skips are normal (control off, their turn, a
                        // draft already waiting…) — only REAL failures get a diag line
                        if (why != null && !why.startsWith("follow-up skipped:")) {
                            com.replymate.core.model.Contact ct0 = c.contacts().get(contactId);
                            AssistantDiag.record(c, contactId,
                                ct0 == null ? "#" + contactId : ct0.displayName, tag, "",
                                AssistantEvent.Stage.GENERATE, safe(why, 90),
                                "follow-up draft not prepared", fixFor(why));
                        }
                        return;
                    }
                    Draft d = r.value.drafts.get(0);
                    com.replymate.core.model.Contact ct = c.contacts().get(contactId);
                    String who = ct == null || ct.displayName == null
                        ? "#" + contactId : ct.displayName;
                    AssistantDiag.record(c, contactId, who, tag, "",
                        AssistantEvent.Stage.GENERATE,
                        "auto follow-up is on for this contact",
                        "follow-up draft #" + d.id + " prepared — waiting for approval"
                            + " (never auto-sends)", "");
                    if (!ListenerStatus.canPostNotifications(c.app())) return;
                    List<Message> recent = c.messages().lastMessages(contactId, 30);
                    Message lastIncoming = null;
                    for (Message m : recent) {
                        if (m != null && m.direction == Direction.INCOMING) lastIncoming = m;
                    }
                    AssistantTargetStore.Target t = AssistantTargetStore.load(c.kv(), contactId);
                    AssistantPlanner.Capability cap = t.usable()
                        ? AssistantPlanner.Capability.DIRECT
                        : AssistantPlanner.Capability.NONE;
                    AssistantNotifier.ensureChannels(c.app());
                    // ONE pop per cycle, same discipline as reply drafts: the
                    // approval cleared the flag, so this follow-up pops once.
                    String alertedKey = AssistantPlanner.alertedKvKey(contactId);
                    boolean fresh = !"1".equals(c.kv().get(alertedKey, "0"));
                    AssistantNotifier.postFollowUp(c.app(), contactId, who,
                        lastIncoming == null
                            ? "" : WatchedApps.labelFor(lastIncoming.channel),
                        d.replyText, d.id, cap, fresh,
                        lastIncoming == null ? null : lastIncoming.body,
                        lastIncoming == null ? 0L : lastIncoming.sentAt,
                        d.createdAt > 0 ? d.createdAt : System.currentTimeMillis());
                    if (fresh) c.kv().put(alertedKey, "1");
                } catch (RuntimeException e) {
                    AssistantDiag.record(c, contactId, "#" + contactId, tag, "",
                        AssistantEvent.Stage.GENERATE,
                        e.getClass().getSimpleName() + ": " + safe(e.getMessage(), 70),
                        "follow-up preparation aborted safely", "");
                }
            }
        });
    }

    /** P-intelligence-16b: a group engagement gate said WAIT or NO_REPLY. Record
     *  the verdict honestly; WAIT defers ONE re-check of the same content (never
     *  a loop — a second WAIT on identical content is marked handled); NO_REPLY
     *  is marked handled so the catch-up sweep stays quiet for the same burst. */
    private static void handleEngagementSkip(final AppContainer c, final long contactId,
                                             final String who, final String incomingHash,
                                             final String verdictReason) {
        String tag = AssistantPlanner.notifTag(contactId);
        com.replymate.core.usecase.ConversationStateService svc = c.conversationStates();
        if (verdictReason.startsWith("WAIT")) {
            if (svc != null && !incomingHash.equals(svc.waitedFor(contactId))) {
                svc.markWaited(contactId, incomingHash);
                AssistantDiag.record(c, contactId, who, tag, "",
                    AssistantEvent.Stage.SCHEDULE,
                    "group conversation still mid-flow (" + verdictReason + ")",
                    "one deferred re-check in 90s — nothing generated, no provider call",
                    "");
                TIMER.postDelayed(new Runnable() {
                    @Override public void run() {
                        Tasks.gen(new Runnable() {
                            @Override public void run() {
                                long tok = JOBS.begin(contactId);
                                try {
                                    generateAndNotify(c, contactId, who, false, tok);
                                } finally {
                                    JOBS.finish(contactId, tok);
                                }
                            }
                        });
                    }
                }, 90_000L);
            } else {
                c.kv().put(AssistantPlanner.hashKvKey(contactId), incomingHash);
                if (svc != null) svc.markSkip(contactId, incomingHash, verdictReason);
                AssistantDiag.record(c, contactId, who, tag, "",
                    AssistantEvent.Stage.SCHEDULE,
                    "still unresolved after the wait (" + verdictReason + ")",
                    "stayed silent and marked handled — a new message re-evaluates", "");
            }
            return;
        }
        c.kv().put(AssistantPlanner.hashKvKey(contactId), incomingHash);
        if (svc != null) {
            if (svc.skippedFor(contactId, incomingHash) != null) return;  // sweep-quiet
            svc.markSkip(contactId, incomingHash, verdictReason);
        }
        AssistantDiag.record(c, contactId, who, tag, "",
            AssistantEvent.Stage.NOTIFY,
            "group burst needed no reply (" + verdictReason + ")",
            "stayed silent by design — no draft, no provider call", "");
    }

    /** Core flow — background thread only. Never throws. */
    static void generateAndNotify(AppContainer c, long contactId, String name,
                                  boolean force, long token) {
        String who = name == null || name.trim().isEmpty() ? "#" + contactId : name;
        String tag = AssistantPlanner.notifTag(contactId);
        try {
            if (!enabled(c) && !force) return;
            // P-intelligence-7 (fresh-install first-message fix): the
            // POST_NOTIFICATIONS runtime approval gates ONLY the alert, never the
            // draft. On a fresh install Android denies it until the owner grants
            // once — a first message swallowed here used to die silently with no
            // draft and no retry. Now the draft generates either way; the alert
            // posts when allowed, and the catch-up re-alerts after the grant.
            boolean canAlert = ListenerStatus.canPostNotifications(c.app());

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
            // P-background-8: the paid call is gated on THIS job still being the
            // conversation's current one — a slow lookup can hold a stale job for
            // seconds, and it must wake up to an abort, not a billed stale draft.
            final long cid = contactId;
            final long tok = token;
            Result<DraftOutcome> r = svc.generateForContact(contactId,
                new DraftService.AbortCheck() {
                    @Override public boolean aborted() {
                        return !JOBS.isCurrent(cid, tok);
                    }
                });
            if (r != null && DraftService.SUPERSEDED_ERROR.equals(r.error)) {
                AssistantDiag.record(c, contactId, who, tag, "",
                    AssistantEvent.Stage.SCHEDULE,
                    "superseded by a newer job for this conversation",
                    "aborted before the provider call (after research)", "");
                return;
            }
            // P-bg-10: the race the pre-call gate cannot see — a message landed
            // WHILE the provider was generating. The stale job's result is
            // discarded with its own honest audit line (the interrupted call
            // was paid once) and the newer job answers instead.
            if (r != null && DraftService.SUPERSEDED_AFTER_CALL_ERROR.equals(r.error)) {
                AssistantDiag.record(c, contactId, who, tag, "",
                    AssistantEvent.Stage.GENERATE,
                    "a newer message arrived while this generation was in flight",
                    "stale result discarded before any draft or alert"
                        + " (the interrupted call was paid once; the newer job answers)", "");
                return;
            }
            // P-intelligence-16b: the group engagement gate refused — WAIT or
            // NO_REPLY. Not an error: the honest verdict is recorded, WAIT gets
            // exactly one deferred re-check, NO_REPLY stays silently handled.
            if (r != null && r.error != null
                    && r.error.startsWith(DraftService.ENGAGEMENT_SKIP_PREFIX)) {
                handleEngagementSkip(c, contactId, who, incomingHash,
                    r.error.substring(DraftService.ENGAGEMENT_SKIP_PREFIX.length()));
                return;
            }
            if (r == null || !r.ok || r.value == null || r.value.drafts.isEmpty()) {
                String reason = safe(r == null ? "no result" : r.error, 90);
                AssistantDiag.record(c, contactId, who, tag, "",
                    AssistantEvent.Stage.GENERATE, reason,
                    "nothing generated, no alert posted", fixFor(reason));
                return;
            }
            Draft d = r.value.drafts.get(0);
            if (!canAlert) {
                // Draft exists (in-app, auditable) — only the alert is gated. The
                // hash is deliberately NOT marked done so retryUnanswered()
                // re-alerts this conversation the moment the approval is granted.
                AssistantDiag.record(c, contactId, who, tag, "",
                    AssistantEvent.Stage.NOTIFY,
                    "'show notifications' approval missing",
                    "draft #" + d.id + " saved — visible in ReplyMate; no alert posted",
                    "allow ReplyMate notifications (the home screen asks once on first open)");
                return;
            }
            c.kv().put(AssistantPlanner.hashKvKey(contactId), incomingHash);
            notifyDraft(c, contactId, who, lastIncoming, d, force);
        } catch (RuntimeException e) {
            AssistantDiag.record(c, contactId, who, tag, "",
                AssistantEvent.Stage.GENERATE,
                e.getClass().getSimpleName() + ": " + safe(e.getMessage(), 70),
                "pipeline aborted safely", "re-open Settings → Diagnostics if it repeats");
        }
    }

    /** Post (or silently refresh) the draft alert for one conversation — shared by
     *  scheduled generation and the approval catch-up. Background thread only. */
    private static void notifyDraft(AppContainer c, long contactId, String who,
                                    Message lastIncoming, Draft d, boolean force) {
        String tag = AssistantPlanner.notifTag(contactId);
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
        // P-background-8 heads-up discipline: ONE audible pop per draft cycle.
        // A scheduled (non-forced) generation alerts only when this conversation
        // hasn't claimed the current cycle yet — burst updates + Regenerate
        // refresh the SAME alert silently. Approve/Copy/fallback and any
        // dismissal of the card clear the flag, so the next genuinely new
        // burst pops again. force (the Regenerate button) never re-pops.
        String alertedKey = AssistantPlanner.alertedKvKey(contactId);
        boolean fresh = !force && !"1".equals(c.kv().get(alertedKey, "0"));
        // P-background-11: the alert names exactly WHAT is being answered — the
        // fire-time latest incoming message (never a stale scheduled one) + its
        // time, and the draft's own generation time.
        // P-intelligence-16b: group drafts carry the engagement verdict's salience —
        // a directed reply names its target; an optional chime-in never masquerades
        // as an answer. 1:1 keeps the default labels byte-identically.
        String titleLabel = "Draft reply for ";
        String contextLabel = "Replying to ";
        if (c.conversationStates() != null) {
            String[] parts = c.conversationStates().lastFor(contactId).split("\\|", -1);
            if (parts.length >= 3 && "REPLY_REQUIRED".equals(parts[0])
                    && !parts[2].isEmpty()) {
                titleLabel = "Reply to " + parts[2] + " in ";
                contextLabel = parts[2] + " said to you — ";
            } else if (parts.length >= 1 && "REPLY_OPTIONAL".equals(parts[0])) {
                titleLabel = "You could chime in — ";
                contextLabel = "In the group — ";
            }
        }
        AssistantNotifier.postWithLabels(c.app(), contactId, who, appLabel, d.replyText,
            d.id, cap, fresh, lastIncoming.body, lastIncoming.sentAt,
            d.createdAt > 0 ? d.createdAt : System.currentTimeMillis(),
            titleLabel, contextLabel);
        if (fresh) c.kv().put(alertedKey, "1");
    }

    /** P-intelligence-7: approval/provider catch-up. A message that arrived while a
     *  prerequisite was missing (POST_NOTIFICATIONS denied on a fresh install, or
     *  no provider configured yet) must not stay dead forever: scan conversations
     *  whose LATEST message is a real incoming text that never produced an alerted
     *  draft, and re-drive them through the normal pipeline. Called after the
     *  owner grants notifications, and after a provider is saved. Idempotent:
     *  conversations already answered are skipped by the same hash + pending-draft
     *  checks the live path uses. */
    public static void retryUnanswered(final AppContainer c) {
        if (c == null || !enabled(c)) return;
        c.kv().put(KV_LAST_SWEEP, String.valueOf(c.clock().now()));
        // P-background-9: the sweep scans every contact's latest state and may
        // schedule generations — it belongs on the GENERATION lane so capture
        // keeps flowing underneath it.
        Tasks.gen(new Runnable() {
            @Override public void run() {
                for (com.replymate.core.model.Contact ct : c.contacts().all()) {
                    try {
                        retryOne(c, ct);
                    } catch (RuntimeException ignored) { /* one bad row never stops the sweep */ }
                }
            }
        });
    }

    /** P-background-9: throttled entry point for automatic triggers (listener
     *  (re)connect, connectivity return). Same sweep as retryUnanswered, skipped
     *  when the last sweep ran less than {@code minIntervalMs} ago. Manual/UI
     *  triggers keep calling the always-run form. */
    public static void retryUnansweredThrottled(AppContainer c, long minIntervalMs) {
        if (c == null || !enabled(c)) return;
        long last;
        try {
            last = Long.parseLong(c.kv().get(KV_LAST_SWEEP, "0"));
        } catch (NumberFormatException nfe) {
            last = 0;
        }
        if (c.clock().now() - last < minIntervalMs) return;
        retryUnanswered(c);
    }

    /** P-background-9: one contact inside the sweep. All DECISIONS live in the
     *  pure, JVM-pinned CatchupPolicy; this driver only gathers state and acts. */
    private static void retryOne(AppContainer c, com.replymate.core.model.Contact ct) {
        java.util.List<Message> lastOne = c.messages().lastMessages(ct.id, 1);
        if (lastOne.isEmpty()) return;
        Message m = lastOne.get(0);

        Draft pending = null;
        for (Draft d : c.drafts().byContact(ct.id, 5)) {
            if (d.status == com.replymate.core.model.DraftStatus.GENERATED) {
                pending = d;
                break;
            }
        }
        // P-background-8: only a REAL waiting draft may be re-alerted — one that
        // was generated against THIS latest message. A message newer than the
        // waiting draft (it arrived while a slow generation was still pending)
        // makes that draft stale: re-alerting it would mark the new message
        // "answered" and its own scheduled job would then skip — the owner would
        // get yesterday's answer pinned to today's question. Stale ⇒ a fresh
        // scheduled generation instead. (Rule carried into CatchupPolicy.)
        boolean waitsOnLatest = pending != null && pending.inReplyToId != null
            && pending.inReplyToId.longValue() == m.id;
        String doneHash = c.kv().get(AssistantPlanner.hashKvKey(ct.id), "");
        boolean alertedArmed =
            "1".equals(c.kv().get(AssistantPlanner.alertedKvKey(ct.id), "0"));

        com.replymate.core.assistant.CatchupPolicy.Action action =
            com.replymate.core.assistant.CatchupPolicy.decide(m, doneHash,
                waitsOnLatest, alertedArmed, c.clock().now(),
                com.replymate.core.assistant.CatchupPolicy.DEFAULT_MAX_AGE_MS);

        switch (action) {
            case SKIP:
                return;

            case SKIP_AGED:
                // Explain ONCE per message (never spam the ring on every sweep).
                String agedKey = KV_AGED_PREFIX + ct.id;
                if (!String.valueOf(m.id).equals(c.kv().get(agedKey, ""))) {
                    c.kv().put(agedKey, String.valueOf(m.id));
                    AssistantDiag.record(c, ct.id, ct.displayName,
                        AssistantPlanner.notifTag(ct.id), "",
                        AssistantEvent.Stage.SCHEDULE,
                        "an unanswered message older than the catch-up window",
                        "no background draft scheduled (a late reply would be noise"
                            + " and a surprise provider bill) — it stays in the chat",
                        "open the conversation to answer it by hand");
                }
                return;

            case RE_ALERT:
                // A draft for exactly this message already waits in-app (generated
                // while alerts were denied): re-alert it — never pay for a
                // duplicate generation.
                if (!ListenerStatus.canPostNotifications(c.app())) return;
                c.kv().put(AssistantPlanner.hashKvKey(ct.id),
                    AssistantPlanner.hashOf(m.body + "|" + m.sentAt + "|" + m.id));
                notifyDraft(c, ct.id, ct.displayName, m, pending, false);
                AssistantDiag.record(c, ct.id, ct.displayName,
                    AssistantPlanner.notifTag(ct.id), "",
                    AssistantEvent.Stage.NOTIFY,
                    "approval granted after the draft was generated",
                    "existing draft re-alerted (no duplicate generation)",
                    "");
                return;

            case RE_ALERT_SILENT:
                // P-background-9: the draft was answered AND the alert flag says
                // its card should be in the shade — but a system restart / app
                // update wiped every posted notification. Bring the SAME card
                // back silently (armed flag ⇒ notifyDraft stays pop-free), with
                // zero provider cost. A swipe clears the flag first, so a
                // deliberately dismissed draft is never re-shown by this path.
                if (!ListenerStatus.canPostNotifications(c.app())) return;
                notifyDraft(c, ct.id, ct.displayName, m, pending, false);
                AssistantDiag.record(c, ct.id, ct.displayName,
                    AssistantPlanner.notifTag(ct.id), "",
                    AssistantEvent.Stage.NOTIFY,
                    "the waiting draft's alert was wiped by a restart/update",
                    "the same draft card was re-posted silently"
                        + " (no duplicate generation, no provider cost)",
                    "");
                return;

            case RE_GENERATE:
            default:
                if (pending != null) {
                    AssistantDiag.record(c, ct.id, ct.displayName,
                        AssistantPlanner.notifTag(ct.id), "",
                        AssistantEvent.Stage.SCHEDULE,
                        "a newer message outdates the waiting draft",
                        "catch-up schedules a fresh generation instead of re-alerting the stale one",
                        "");
                }
                schedule(c, new IngestReport.PingRequest(
                    ct.id, ct.displayName, m.body, m.sentAt));
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
            return "network was down — ReplyMate retries by itself when"
                + " connectivity returns, on listener rebind, and on the next message";
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
