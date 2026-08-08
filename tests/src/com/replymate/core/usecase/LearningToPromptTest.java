package com.replymate.core.usecase;

import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.model.StyleSignal;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/** P-intelligence-2 (item 7 — learning must CHANGE FUTURE OUTPUT, item 8 — audit
 *  accuracy): the full chain is proven here — a recorded signal must reach the
 *  ACTUAL request sent to the provider for THAT contact and must appear, with its
 *  evidence, in the stored Prompt Audit snapshot. "Logged but never used" is a bug
 *  this suite exists to catch. */
public final class LearningToPromptTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.StyleSettingStoreFake settings;
    private Fakes.LearningStoreFake signals;
    private Fakes.KvStoreFake learnKv;
    private LearningService learning;
    private DraftService service;
    private Fakes.FakeProvider provider;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        Fakes.StyleStoreFake styles = new Fakes.StyleStoreFake();
        Fakes.DraftStoreFake drafts = new Fakes.DraftStoreFake();
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        ProfileService profiles = new ProfileService(kv);
        settings = new Fakes.StyleSettingStoreFake();
        signals = new Fakes.LearningStoreFake();
        learnKv = new Fakes.KvStoreFake();
        learning = Fakes.learningService(signals, learnKv);
        provider = Fakes.FakeProvider.returning("didn't get the job in the end, sadly");
        service = new DraftService(contacts, messages, styles, profiles, drafts,
            new Fakes.UsageStoreFake(), new Fakes.GatewayFake(provider),
            Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(settings, learning), learning,
            new com.replymate.core.memory.MemoryService(
                new Fakes.MemoryStoreFake(), messages, kv, Fakes.FIXED_CLOCK));
        contacts.put(Fakes.contact(1, "Amara"));
        messages.add(Fakes.msg(1, Direction.INCOMING, "any luck with the interview?"));
    }

    private static void sig(LearningService svc, Contact c, StyleSignal.Kind k, String d) {
        svc.record(c, k, d, null);
    }

    private Result<DraftOutcome> generate() {
        return service.generateForContact(1L);
    }

    private String system() { return provider.lastRequest.system; }

    private String snapshotOf(Result<DraftOutcome> r) {
        Draft d = r.value.drafts.get(0);
        return d.promptSnapshotJson;
    }

    @Test public void repeatedShorterEditsReachTheNextPromptWithEvidence() {
        Contact c = contacts.get(1L);
        sig(learning, c, StyleSignal.Kind.EDITED, "shorter");
        sig(learning, c, StyleSignal.Kind.EDITED, "shorter");
        sig(learning, c, StyleSignal.Kind.EDITED, "shorter");
        Result<DraftOutcome> r = generate();
        assertTrue(r.ok);
        assertTrue("the learned correction must reach the provider prompt",
            system().contains("keep replies noticeably shorter"));
        String snap = snapshotOf(r);
        assertTrue("audit credits the hint", snap.contains("learned: keep replies noticeably shorter"));
        assertTrue("audit shows the raw feedback counters behind it",
            snap.contains("feedback so far for Amara: 0 approved \\u00b7 3 edited")
                || snap.contains("feedback so far for Amara: 0 approved · 3 edited"));
    }

    @Test public void steadyApprovalsStrengthenTheCurrentStyle() {
        Contact c = contacts.get(1L);
        for (int i = 0; i < 5; i++) sig(learning, c, StyleSignal.Kind.APPROVED, "");
        assertTrue(generate().ok);
        assertTrue(system().contains("the current style is landing well — keep it consistent"));
    }

    @Test public void regenFatigueProducesAVarietyHint() {
        Contact c = contacts.get(1L);
        sig(learning, c, StyleSignal.Kind.APPROVED, "");
        for (int i = 0; i < 4; i++) sig(learning, c, StyleSignal.Kind.REGENERATED, "");
        assertTrue(generate().ok);
        assertTrue(system().contains("vary the wording between options more"));
    }

    @Test public void rejectStreakProducesAConservativeHint() {
        Contact c = contacts.get(1L);
        sig(learning, c, StyleSignal.Kind.APPROVED, "");
        for (int i = 0; i < 3; i++) sig(learning, c, StyleSignal.Kind.REJECTED, "");
        assertTrue(generate().ok);
        assertTrue(system().contains("be more conservative"));
    }

    @Test public void explicitContactSettingBlocksTheLearnedGuessFromThePrompt() {
        Contact c = contacts.get(1L);
        sig(learning, c, StyleSignal.Kind.EDITED, "shorter");
        sig(learning, c, StyleSignal.Kind.EDITED, "shorter");
        sig(learning, c, StyleSignal.Kind.EDITED, "shorter");
        settings.put(1L, "length", "2");
        Result<DraftOutcome> r = generate();
        assertTrue(r.ok);
        assertFalse("explicit contact length must win over the learned guess",
            system().contains("keep replies noticeably shorter"));
        String snap = snapshotOf(r);
        assertTrue("audit shows the suppression instead",
            snap.contains("learned hint suppressed"));
        assertTrue("…but the feedback trail itself stays visible",
            snap.contains("feedback so far for Amara"));
    }

    @Test public void signalsReachOnlyTheirOwnContactsPrompt() {
        Contact c1 = contacts.get(1L);
        contacts.put(Fakes.contact(2, "Bank Client"));
        messages.add(Fakes.msg(2, Direction.INCOMING, "invoice reminder"));
        sig(learning, c1, StyleSignal.Kind.EDITED, "shorter");
        sig(learning, c1, StyleSignal.Kind.EDITED, "shorter");
        sig(learning, c1, StyleSignal.Kind.EDITED, "shorter");
        assertTrue(generate().ok);                       // Amara — hint lands
        assertTrue(system().contains("keep replies noticeably shorter"));
        assertTrue(service.generateForContact(2L).ok);   // Bank Client — clean
        assertFalse("contact 2 must never inherit contact 1's learned pattern",
            system().contains("keep replies noticeably shorter"));
    }
}
