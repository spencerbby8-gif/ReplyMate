package com.replymate.core.assistant;

import com.replymate.core.assistant.CatchupPolicy.Action;
import com.replymate.core.listener.ListenerFilter;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.core.model.Source;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-background-9: the full catch-up sweep matrix. The sweep is the recovery path
 *  after process death / rebind / connectivity return — every wrong answer has a
 *  real user cost (lost draft, paid duplicate, spammed re-alert, awkward-late
 *  reply), so the whole matrix is pinned here. */
public final class CatchupPolicyTest {

    private static final long NOW = 1_000_000_000_000L;   // ~2001-09 epoch ms
    private static final long AGE = CatchupPolicy.DEFAULT_MAX_AGE_MS;

    private static Message msg(Direction dir, String body, long sentAt, long id) {
        Message m = new Message();
        m.contactId = 7L;
        m.direction = dir;
        m.body = body;
        m.sentAt = sentAt;
        m.id = id;
        m.source = Source.LISTENER;
        return m;
    }

    private static String doneHashOf(Message m) {
        return AssistantPlanner.hashOf(m.body + "|" + m.sentAt + "|" + m.id);
    }

    /* ----------------------------------------------------- non-conversation */

    @Test public void nullAndOutgoingAndPlaceholderSkip() {
        assertEquals(Action.SKIP,
            CatchupPolicy.decide(null, "", false, false, NOW, AGE));
        assertEquals(Action.SKIP,
            CatchupPolicy.decide(
                msg(Direction.OUTGOING, "on my way", NOW - 1000, 9),
                "", false, false, NOW, AGE));
        assertEquals(Action.SKIP,
            CatchupPolicy.decide(
                msg(Direction.INCOMING, ListenerFilter.MEDIA_PLACEHOLDER, NOW - 1000, 9),
                "", false, false, NOW, AGE));
    }

    /* -------------------------------------------- live unanswered recovery */

    @Test public void unansweredWithNoWaitingDraftRegenerates() {
        // message stored while the process was dead / generation crashed
        Message m = msg(Direction.INCOMING, "you there?", NOW - 60_000, 11);
        assertEquals(Action.RE_GENERATE,
            CatchupPolicy.decide(m, "", false, false, NOW, AGE));
    }

    @Test public void unansweredWithStaleDraftRegenerates() {
        // pending draft exists but NOT for this latest message (waitsOnLatest=false)
        Message newer = msg(Direction.INCOMING, "hello??", NOW - 30_000, 12);
        Message older = msg(Direction.INCOMING, "you there?", NOW - 90_000, 11);
        assertEquals(Action.RE_GENERATE,
            CatchupPolicy.decide(newer, doneHashOf(older), false, false, NOW, AGE));
    }

    @Test public void unansweredWithWaitingDraftRealerts() {
        // generated while alerts were denied: re-alert, never re-pay
        Message m = msg(Direction.INCOMING, "you there?", NOW - 60_000, 11);
        assertEquals(Action.RE_ALERT,
            CatchupPolicy.decide(m, "", true, false, NOW, AGE));
    }

    /* ------------------------------------------------- reboot/update re-post */

    @Test public void answeredWaitingDraftWithArmedFlagRealertsSilently() {
        // draft generated + alerted, card wiped by a system restart — bring it back
        Message m = msg(Direction.INCOMING, "you there?", NOW - 60_000, 11);
        assertEquals(Action.RE_ALERT_SILENT,
            CatchupPolicy.decide(m, doneHashOf(m), true, true, NOW, AGE));
    }

    @Test public void swipedWaitingDraftIsNeverReshown() {
        // same bits as above minus the armed flag = the owner swiped it away;
        // that is a decision — the sweep must respect it forever
        Message m = msg(Direction.INCOMING, "you there?", NOW - 60_000, 11);
        assertEquals(Action.SKIP,
            CatchupPolicy.decide(m, doneHashOf(m), true, false, NOW, AGE));
    }

    @Test public void answeredWithNothingWaitingSkips() {
        Message m = msg(Direction.INCOMING, "you there?", NOW - 60_000, 11);
        assertEquals(Action.SKIP,
            CatchupPolicy.decide(m, doneHashOf(m), false, true, NOW, AGE));
        assertEquals(Action.SKIP,
            CatchupPolicy.decide(m, doneHashOf(m), false, false, NOW, AGE));
    }

    /* ---------------------------------------------------------- age guard */

    @Test public void ancientUnansweredMessageIsAgedOut() {
        Message old = msg(Direction.INCOMING, "you there?", NOW - AGE - 1000, 11);
        assertEquals(Action.SKIP_AGED,
            CatchupPolicy.decide(old, "", false, false, NOW, AGE));
    }

    @Test public void exactAgeBoundaryStillRegenerates() {
        Message edge = msg(Direction.INCOMING, "you there?", NOW - AGE, 11);
        assertEquals("boundary is inclusive — only strictly older ages out",
            Action.RE_GENERATE, CatchupPolicy.decide(edge, "", false, false, NOW, AGE));
    }

    @Test public void disabledAgeCapNeverAgesOut() {
        Message old = msg(Direction.INCOMING, "you there?", NOW - 365L * 24 * 3600 * 1000, 11);
        assertEquals(Action.RE_GENERATE,
            CatchupPolicy.decide(old, "", false, false, NOW, 0));
    }

    @Test public void ageGuardNeverSuppressesRealerts() {
        // a waiting draft re-alerts regardless of message age — it costs nothing
        Message old = msg(Direction.INCOMING, "you there?", NOW - AGE - 5000, 11);
        assertEquals(Action.RE_ALERT,
            CatchupPolicy.decide(old, "", true, false, NOW, AGE));
        assertEquals(Action.RE_ALERT_SILENT,
            CatchupPolicy.decide(old, doneHashOf(old), true, true, NOW, AGE));
    }
}
