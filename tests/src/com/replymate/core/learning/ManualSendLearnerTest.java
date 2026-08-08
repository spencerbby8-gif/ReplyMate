package com.replymate.core.learning;

import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.model.DraftStatus;
import com.replymate.core.model.Message;
import com.replymate.core.model.StyleSignal;
import com.replymate.fakes.Fakes;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-1: learning from MANUALLY SENT replies. The owner typing their own
 *  answer in the chat app (visible via MessagingStyle history) teaches us: same
 *  words → APPROVED; different words → EDITED with the correction classification.
 *  Gate, freshness, dedupe and placeholder rules are all pinned. */
public final class ManualSendLearnerTest {

    private Fakes.LearningStoreFake store;
    private Fakes.KvStoreFake kv;
    private LearningService learning;
    private Fakes.MessageStoreFake messages;
    private Fakes.DraftStoreFake drafts;

    @Before public void setUp() {
        store = new Fakes.LearningStoreFake();
        kv = new Fakes.KvStoreFake();
        learning = Fakes.learningService(store, kv);
        messages = new Fakes.MessageStoreFake();
        drafts = new Fakes.DraftStoreFake();
    }

    private static Contact contact(long id) { return Fakes.contact(id, "Ada"); }

    private static Message msg(long contactId, Direction dir, String body, long at) {
        Message m = Fakes.msg(contactId, dir, body);
        m.sentAt = at;
        return m;
    }

    private static Draft draft(long contactId, String text, DraftStatus status, long createdAt) {
        Draft d = new Draft();
        d.contactId = contactId;
        d.replyText = text;
        d.status = status;
        d.createdAt = createdAt;
        return d;
    }

    private List<StyleSignal> signals(long contactId) {
        return store.byContact(contactId, 100);
    }

    @Test public void ownWordsAfterADraftRecordTheCorrection() {
        Contact c = contact(1);
        drafts.insert(draft(1, "sure, 8pm works for me", DraftStatus.GENERATED, 1000));
        messages.add(msg(1, Direction.OUTGOING, "9 then", 2000));

        ManualSendLearner.Result r = ManualSendLearner.evaluate(
            c, messages, drafts, learning, kv, Fakes.FIXED_CLOCK);
        assertEquals(ManualSendLearner.Outcome.LEARNED_EDITED, r.outcome);
        assertEquals(1L, r.draftId);
        List<StyleSignal> sig = signals(1);
        assertEquals(1, sig.size());
        assertEquals(StyleSignal.Kind.EDITED, sig.get(0).kind);
        assertTrue(sig.get(0).detail.startsWith("manual:"));
        assertTrue("shorter manual text feeds the same hint counters",
            sig.get(0).detail.contains("shorter"));
    }

    @Test public void identicalManualSendRecordsApproval() {
        Contact c = contact(2);
        drafts.insert(draft(2, "omw now", DraftStatus.GENERATED, 1000));
        messages.add(msg(2, Direction.OUTGOING, "omw now", 3000));
        assertEquals(ManualSendLearner.Outcome.LEARNED_APPROVED,
            ManualSendLearner.evaluate(c, messages, drafts, learning, kv,
                Fakes.FIXED_CLOCK).outcome);
        assertEquals(StyleSignal.Kind.APPROVED, signals(2).get(0).kind);
        assertEquals("sent-manually-same-words", signals(2).get(0).detail);
    }

    @Test public void oneDraftLearnsOnceEvenIfEvaluatedRepeatedly() {
        Contact c = contact(3);
        drafts.insert(draft(3, "sure", DraftStatus.GENERATED, 1000));
        messages.add(msg(3, Direction.OUTGOING, "sure thing boss", 2000));
        ManualSendLearner.evaluate(c, messages, drafts, learning, kv, Fakes.FIXED_CLOCK);
        ManualSendLearner.Result again = ManualSendLearner.evaluate(
            c, messages, drafts, learning, kv, Fakes.FIXED_CLOCK);
        assertEquals(ManualSendLearner.Outcome.ALREADY_LEARNED, again.outcome);
        assertEquals(1, signals(3).size());
    }

    @Test public void draftsOlderThanSeventyTwoHoursNeverAbsorbNewSends() {
        Contact c = contact(4);
        long old = 1_000;
        drafts.insert(draft(4, "old suggestion", DraftStatus.GENERATED, old));
        messages.add(msg(4, Direction.OUTGOING, "hi", old + ManualSendLearner.MAX_AGE_MS + 1));
        assertEquals(ManualSendLearner.Outcome.NO_LIVE_DRAFT,
            ManualSendLearner.evaluate(c, messages, drafts, learning, kv,
                Fakes.FIXED_CLOCK).outcome);
        assertTrue(signals(4).isEmpty());
    }

    @Test public void consumedDraftsAndNonGeneratedStatesAreUntouched() {
        Contact c = contact(5);
        drafts.insert(draft(5, "already copied", DraftStatus.COPIED, 1000));
        drafts.insert(draft(5, "rejected earlier", DraftStatus.SENT, 1000));
        messages.add(msg(5, Direction.OUTGOING, "my own words", 2000));
        assertEquals(ManualSendLearner.Outcome.NO_LIVE_DRAFT,
            ManualSendLearner.evaluate(c, messages, drafts, learning, kv,
                Fakes.FIXED_CLOCK).outcome);
        assertTrue(signals(5).isEmpty());
    }

    @Test public void placeholderOutgoingIsIgnored() {
        Contact c = contact(6);
        drafts.insert(draft(6, "nice photo!", DraftStatus.GENERATED, 1000));
        Message out = msg(6, Direction.OUTGOING,
            com.replymate.core.model.ContentKind.IMAGE.placeholder(), 2000);
        out.contentKind = com.replymate.core.model.ContentKind.IMAGE.wire;
        messages.add(out);
        assertEquals(ManualSendLearner.Outcome.PLACEHOLDER,
            ManualSendLearner.evaluate(c, messages, drafts, learning, kv,
                Fakes.FIXED_CLOCK).outcome);
        assertTrue(signals(6).isEmpty());
    }

    @Test public void learningGateIsHonoredButTheMarkerStillDedupes() {
        Contact c = contact(7);
        learning.setOff(7, true);
        drafts.insert(draft(7, "sure", DraftStatus.GENERATED, 1000));
        messages.add(msg(7, Direction.OUTGOING, "actually no", 2000));
        assertEquals(ManualSendLearner.Outcome.LEARNED_EDITED,
            ManualSendLearner.evaluate(c, messages, drafts, learning, kv,
                Fakes.FIXED_CLOCK).outcome);
        assertTrue("gate closed → no signal stored", signals(7).isEmpty());
        assertEquals("1", kv.get(ManualSendLearner.markerKey(1), "0"));
        learning.setOff(7, false);
        assertEquals("and it never re-fires for that draft",
            ManualSendLearner.Outcome.ALREADY_LEARNED,
            ManualSendLearner.evaluate(c, messages, drafts, learning, kv,
                Fakes.FIXED_CLOCK).outcome);
    }

    @Test public void draftCreatedAfterTheManualSendCannotLearnFromIt() {
        Contact c = contact(8);
        messages.add(msg(8, Direction.OUTGOING, "later g", 1000));
        drafts.insert(draft(8, "later g", DraftStatus.GENERATED, 5000));  // newer draft
        assertEquals(ManualSendLearner.Outcome.NO_LIVE_DRAFT,
            ManualSendLearner.evaluate(c, messages, drafts, learning, kv,
                Fakes.FIXED_CLOCK).outcome);
        assertTrue(signals(8).isEmpty());
    }

    @Test public void noCrossContactLeakBetweenLearners() {
        Contact c9 = contact(9);
        Contact c10 = contact(10);
        drafts.insert(draft(9, "nine's draft", DraftStatus.GENERATED, 1000));
        drafts.insert(draft(10, "ten's draft", DraftStatus.GENERATED, 1000));
        messages.add(msg(10, Direction.OUTGOING, "my own answer", 2000));
        ManualSendLearner.evaluate(c10, messages, drafts, learning, kv, Fakes.FIXED_CLOCK);
        assertTrue("contact 9 got nothing from contact 10's manual send",
            signals(9).isEmpty());
        assertEquals(1, signals(10).size());
    }
}
