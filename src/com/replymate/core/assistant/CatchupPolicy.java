package com.replymate.core.assistant;

import com.replymate.core.listener.ListenerFilter;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;

/** P-background-9: the catch-up sweep's decision rule, extracted PURE so the whole
 *  matrix is JVM-pinned (AssistantRunner.retryOne becomes a thin driver over it).
 *
 *  The sweep runs on listener (re)connect and on connectivity return — the two
 *  moments a dead-time message / lost generation can be recovered WITHOUT the
 *  owner opening the app. For each conversation's LATEST state it decides:
 *
 *    SKIP           — nothing owed: outgoing latest, placeholder/unreadable body,
 *                     or the latest incoming already produced an alerted draft
 *                     and there is nothing waiting to re-show. ALSO the answer
 *                     for a waiting draft the owner SWIPED AWAY (alert flag
 *                     cleared): a swipe is a decision, never re-pop it.
 *    RE_ALERT       — a draft for exactly this latest message exists but was
 *                     never alerted (fresh-install denied permission, provider
 *                     saved later): re-alert it, no duplicate paid generation.
 *    RE_ALERT_SILENT— the draft was generated AND alerted (hash done) and is
 *                     still waiting, while the armed alert flag says its card
 *                     should be in the shade but a system restart / app update
 *                     wiped it: re-post the SAME card silently (no pop, no cost).
 *    RE_GENERATE    — the latest incoming is unanswered and has no waiting draft
 *                     (message stored while the process was dead, generation
 *                     crashed, alert impossible): schedule the normal debounced
 *                     generation — the live path's own staleness gates (hash +
 *                     coalescer) make this idempotent.
 *    SKIP_AGED      — unanswered but OLDER than the catch-up window: replying
 *                     days later is noise (and a surprise provider bill); leave
 *                     it for the owner to open by hand. Live-path generation is
 *                     NEVER age-capped — this rule guards sweep catch-up only.
 *
 *  "waitsOnLatest" = the newest GENERATED draft's inReplyToId IS the latest
 *  incoming message id (the 1.5.8 stale-draft guard, unchanged).
 */
public final class CatchupPolicy {

    public enum Action { SKIP, SKIP_AGED, RE_ALERT, RE_ALERT_SILENT, RE_GENERATE }

    /** Sweep re-generation window: messages older than this are never auto-driven
     *  by a catch-up sweep (cost + awkward-late-reply guard). */
    public static final long DEFAULT_MAX_AGE_MS = 48L * 60 * 60 * 1000;

    private CatchupPolicy() { }

    /**
     * @param lastIncoming the conversation's LATEST message (any direction; null-safe)
     * @param doneHash     the persisted hash of the last alerted generation ("" when none)
     * @param waitsOnLatest a GENERATED draft exists whose inReplyToId == lastIncoming.id
     * @param alertedArmed  per-conversation alert flag still set (card presumed shown)
     * @param nowMs        clock now
     * @param maxAgeMs     sweep age cap for RE_GENERATE (0/disabled ⇒ no cap)
     */
    public static Action decide(Message lastIncoming, String doneHash,
                                boolean waitsOnLatest, boolean alertedArmed,
                                long nowMs, long maxAgeMs) {
        if (lastIncoming == null || lastIncoming.direction != Direction.INCOMING) {
            return Action.SKIP;
        }
        if (lastIncoming.body == null || ListenerFilter.isPlaceholder(lastIncoming.body)) {
            return Action.SKIP;
        }

        boolean answered = !AssistantPlanner.needsReply(lastIncoming, doneHash);

        if (waitsOnLatest) {
            if (!answered) {
                // draft exists for this exact message but the alert never went out
                return Action.RE_ALERT;
            }
            // answered + waiting draft: the card is either still up (harmless
            // silent refresh) or was wiped by a restart/update (bring it back,
            // silently). A swipe clears the armed flag first ⇒ SKIP, always.
            return alertedArmed ? Action.RE_ALERT_SILENT : Action.SKIP;
        }

        if (answered) return Action.SKIP;

        if (maxAgeMs > 0 && lastIncoming.sentAt > 0
                && nowMs - lastIncoming.sentAt > maxAgeMs) {
            return Action.SKIP_AGED;
        }
        return Action.RE_GENERATE;
    }
}
