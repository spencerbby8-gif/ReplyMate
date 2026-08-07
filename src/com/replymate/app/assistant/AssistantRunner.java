package com.replymate.app.assistant;

import android.os.Handler;
import android.os.Looper;
import com.replymate.app.di.AppContainer;
import com.replymate.app.listener.ListenerStatus;
import com.replymate.core.assistant.AssistantPlanner;
import com.replymate.core.listener.BatchWindow;
import com.replymate.core.listener.DiagnosticsRing;
import com.replymate.core.listener.IngestCoordinator;
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

/** P-background: debounced, deduped BACKGROUND generation. When a watched chat's
 *  burst settles (same BatchWindow as pings), this generates ONE draft via the
 *  regular DraftService.generateForContact — which enforces every existing rule
 *  (private contact ⇒ no AI ever, per-contact AI off ⇒ skip, no provider / no
 *  readable latest incoming ⇒ honest error) — and posts the assistant alert.
 *
 *  It sends NOTHING by itself; a human Approve tap is the only send path.
 *  Battery rules: zero polling, zero wake-locks, one provider call per NEW
 *  incoming burst per contact (content-hash dedupe), failures logged to the
 *  Diagnostics ring instead of fake alerts. */
public final class AssistantRunner {

    public static final String KV_ENABLED = "assistant.enabled";

    private static final Handler HANDLER = new Handler(Looper.getMainLooper());
    private static final Map<Long, Runnable> PENDING = new HashMap<Long, Runnable>();

    private AssistantRunner() {
    }

    public static boolean enabled(AppContainer c) {
        return "1".equals(c.kv().get(KV_ENABLED, "1"));
    }

    /** Debounced schedule alongside the message ping (burst ⇒ one run per contact). */
    public static void schedule(final AppContainer c, final IngestReport.PingRequest ping) {
        if (ping == null) return;
        final long contactId = ping.contactId;
        Runnable previous;
        synchronized (PENDING) {
            previous = PENDING.remove(contactId);
        }
        if (previous != null) HANDLER.removeCallbacks(previous);

        Runnable run = new Runnable() {
            @Override public void run() {
                synchronized (PENDING) {
                    PENDING.remove(contactId);
                }
                generateAndNotify(c, contactId, ping.displayName, false);
            }
        };
        synchronized (PENDING) {
            PENDING.put(contactId, run);
        }
        long due = BatchWindow.dueAt(ping.latestTs);
        long delay = BatchWindow.delayFrom(System.currentTimeMillis(), due) + 300;
        HANDLER.postDelayed(run, delay);
    }

    /** The Regenerate button: force a fresh draft and REPLACE the alert in place. */
    public static void regenerateNow(AppContainer c, long contactId, String name) {
        generateAndNotify(c, contactId, name, true);
    }

    /** Core flow — safe to call from any bg thread. Never throws. */
    static void generateAndNotify(AppContainer c, long contactId, String name, boolean force) {
        try {
            if (!enabled(c)) return;
            if (!ListenerStatus.canPostNotifications(c.app())) {
                // Without the permission we can't even show the result honestly.
                ring(c, "assistant skipped · notifications permission missing");
                return;
            }

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

            DraftService svc = c.draftService();
            Result<DraftOutcome> r = svc.generateForContact(contactId);
            if (r == null || !r.ok || r.value == null || r.value.drafts.isEmpty()) {
                // Honest by design: gates (private/AI-off/no-provider/unreadable)
                // land in the diagnostics ring, never as a fake "reply ready" alert.
                ring(c, "assistant gen skip · " + safe(r == null ? "null" : r.error, 60));
                return;
            }
            c.kv().put(AssistantPlanner.hashKvKey(contactId), incomingHash);

            Draft d = r.value.drafts.get(0);
            String appLabel = WatchedApps.labelFor(lastIncoming.channel);
            AssistantTargetStore.Target t = AssistantTargetStore.load(c.kv(), contactId);
            AssistantPlanner.Capability cap = t.usable()
                ? AssistantPlanner.Capability.DIRECT : AssistantPlanner.Capability.NONE;

            AssistantNotifier.ensureChannels(c.app());
            AssistantNotifier.post(c.app(), contactId,
                name == null || name.trim().isEmpty() ? "this chat" : name,
                appLabel, d.replyText, d.id, cap);
        } catch (RuntimeException e) {
            ring(c, "assistant error · " + e.getClass().getSimpleName());
        }
    }

    private static void ring(AppContainer c, String line) {
        try {
            c.kv().put(IngestCoordinator.KV_RING, DiagnosticsRing.append(
                c.kv().get(IngestCoordinator.KV_RING, ""), c.clock().now(), line));
        } catch (RuntimeException ignored) { }
    }

    private static String safe(String s, int max) {
        if (s == null) return "?";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
