package com.replymate.core.usecase;

import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.model.DraftStatus;
import com.replymate.core.model.StyleSignal;
import com.replymate.core.prompt.ComposeKind;
import com.replymate.core.style.StyleControls;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-14, topic 3: INTENTIONAL GENERATION PROOF. The owner rejects
 *  stored values as evidence — every assertion here fires through the REAL
 *  compose pipeline ({@link DraftService#composeForContact}, the same entry
 *  point the Conversation screen calls) and inspects the exact ChatRequest
 *  handed to the provider: the intention-specific task text on the wire, the
 *  SAME voice/memory/learning/Search/reasoning machinery as a reply, honest
 *  admission failures, one-current-draft semantics — and that a composed draft
 *  is still just a DRAFT (GENERATED, never anchored as a reply, never sent). */
public final class IntentionalComposeTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.DraftStoreFake drafts;
    private Fakes.UsageStoreFake usage;
    private Fakes.KvStoreFake kv;
    private ProfileService profiles;
    private Fakes.StyleSettingStoreFake settings;
    private Fakes.LearningStoreFake learningStore;
    private LearningService learning;
    private com.replymate.core.memory.MemoryService memory;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        drafts = new Fakes.DraftStoreFake();
        usage = new Fakes.UsageStoreFake();
        kv = new Fakes.KvStoreFake();
        profiles = new ProfileService(kv);
        settings = new Fakes.StyleSettingStoreFake();
        learningStore = new Fakes.LearningStoreFake();
        learning = Fakes.learningService(learningStore, new Fakes.KvStoreFake());
        memory = new com.replymate.core.memory.MemoryService(
            new Fakes.MemoryStoreFake(), messages, kv, Fakes.FIXED_CLOCK);
    }

    private DraftService service(Fakes.GatewayFake gateway) {
        return new DraftService(contacts, messages, new Fakes.StyleStoreFake(), profiles,
            drafts, usage, gateway, Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(settings, learning), learning, memory);
    }

    private static Result<DraftOutcome> compose(DraftService svc, long id, ComposeKind k) {
        return svc.composeForContact(id, k);
    }

    /* 1 ───────────── FOLLOW-UP: my unanswered outgoing is quoted on the wire,
       the draft is generation-parity complete and NOT anchored on their message. */
    @Test public void followUpBumpsMyUnansweredMessageVerbatimOnTheWire() {
        contacts.put(Fakes.contact(1, "Ada"));
        messages.add(Fakes.msg(1, Direction.INCOMING, "let me check my schedule"));
        messages.add(Fakes.msg(1, Direction.OUTGOING, "no wahala — did the landlord call back?"));
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("bump draft");
        DraftService svc = service(new Fakes.GatewayFake(p));

        Result<DraftOutcome> r = compose(svc, 1, ComposeKind.FOLLOW_UP);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        String task = p.lastRequest.task.text;
        assertTrue("the task names the unanswered state", task.contains("still UNANSWERED by Ada"));
        assertTrue("my exact unanswered outgoing rides the task",
            task.contains("did the landlord call back?"));
        assertTrue(task.contains("follow-up"));
        assertTrue("no guilt-tripping is a hard instruction on the wire",
            task.contains("no guilt-tripping"));

        Draft d = r.value.drafts.get(0);
        assertNull("an intentional draft is NOT anchored on their message", d.inReplyToId);
        assertTrue(d.promptSnapshotJson.contains("compose:follow_up"));
        assertTrue(d.promptSnapshotJson.contains("intentional generation: compose:follow_up"));
        assertEquals("still just a draft — approval remains mandatory",
            DraftStatus.GENERATED, d.status);
    }

    /* 2 ───────────── CLARIFY: their ambiguous message is quoted, and the SAME
       Search gate as a reply attaches native search when the question needs it. */
    @Test public void clarifyQuotesTheirsAndRunsTheSameSearchGate() {
        contacts.put(Fakes.contact(1, "Ada"));
        messages.add(Fakes.msg(1, Direction.INCOMING, "who won the arsenal game last night?"));
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("which competition do you mean?");
        DraftService svc = service(new Fakes.GatewayFake(p));

        Result<DraftOutcome> r = compose(svc, 1, ComposeKind.CLARIFY);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        String task = p.lastRequest.task.text;
        assertTrue(task.contains("clarifying question"));
        assertTrue("their exact ambiguous message is quoted",
            task.contains("who won the arsenal game last night?"));
        assertTrue("the SAME Search gate as a reply fires for a clarification",
            p.lastRequest.opts.search);
        String snap = r.value.drafts.get(0).promptSnapshotJson;
        assertTrue(snap.contains("compose:clarify"));
        assertTrue("the request of the provider's native search is audited",
            snap.contains("requested the provider's native web search"));
    }

    /* 3 ───────────── CONTINUE: same-topic forward motion, anchored on the
       latest message regardless of direction. */
    @Test public void continueMovesTheSameTopicForwardOnTheWire() {
        contacts.put(Fakes.contact(1, "Ada"));
        messages.add(Fakes.msg(1, Direction.INCOMING, "the landlord finally called about the rent"));
        messages.add(Fakes.msg(1, Direction.OUTGOING, "oha — what did he say?"));
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("continuation draft");
        DraftService svc = service(new Fakes.GatewayFake(p));

        Result<DraftOutcome> r = compose(svc, 1, ComposeKind.CONTINUE);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        String task = p.lastRequest.task.text;
        assertTrue(task.contains("CONTINUING that same topic naturally"));
        assertTrue("anchored on the latest usable message (mine here)",
            task.contains("oha — what did he say?"));
        assertTrue("no re-answer is a hard instruction on the wire",
            task.contains("do not re-answer the quoted message"));
        assertTrue(r.value.drafts.get(0).promptSnapshotJson.contains("compose:continue"));
    }

    /* 4 ───────────── OPENER: admitted with an EMPTY thread, pinned memory on
       the wire, and — no anchor — nothing to search. */
    @Test public void openerNeedsNoAnchorUsesPinnedMemoryAndStaysOffTheWeb() {
        contacts.put(Fakes.contact(1, "Ada"));
        memory.replacePinnedFacts(1, "ADA-SHOP-FACT: mum's shop is in Wuse 2");
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("opener draft");
        DraftService svc = service(new Fakes.GatewayFake(p));

        Result<DraftOutcome> r = compose(svc, 1, ComposeKind.OPENER);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        String task = p.lastRequest.task.text;
        assertTrue(task.contains("opening message to Ada"));
        assertTrue("pinned contact memory rides an opener exactly like a reply",
            p.lastRequest.system.contains("ADA-SHOP-FACT: mum's shop is in Wuse 2"));
        assertFalse("no anchor means nothing to look up — Search stays off",
            p.lastRequest.opts.search);
        assertNull(r.value.drafts.get(0).inReplyToId);
        assertTrue(r.value.drafts.get(0).promptSnapshotJson.contains("compose:opener"));
    }

    /* 5 ───────────── ADMISSION HONESTY: every inadmissible intention fails
       with a plain-language reason, never calls the provider, never leaves
       a draft behind. */
    @Test public void admissionFailuresExplainAndTouchNothing() {
        contacts.put(Fakes.contact(1, "Ada"));
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("x");
        DraftService svc = service(new Fakes.GatewayFake(p));

        Result<DraftOutcome> noOutgoing = compose(svc, 1, ComposeKind.FOLLOW_UP);
        assertFalse(noOutgoing.ok);
        assertTrue(noOutgoing.error.contains("Follow-up needs a message FROM YOU"));

        Result<DraftOutcome> noIncoming = compose(svc, 1, ComposeKind.CLARIFY);
        assertFalse(noIncoming.ok);
        assertTrue(noIncoming.error.contains("Nothing from Ada to clarify yet"));

        Result<DraftOutcome> noThread = compose(svc, 1, ComposeKind.CONTINUE);
        assertFalse(noThread.ok);
        assertTrue(noThread.error.contains("No conversation to continue with Ada yet"));

        // their message is the fresh one — bumping now would be wrong, say so
        messages.add(Fakes.msg(1, Direction.OUTGOING, "send the account number"));
        messages.add(Fakes.msg(1, Direction.INCOMING, "which bank again?"));
        Result<DraftOutcome> theirTurn = compose(svc, 1, ComposeKind.FOLLOW_UP);
        assertFalse(theirTurn.ok);
        assertTrue(theirTurn.error.contains("still fresh"));

        assertEquals("an admission failure never calls the provider", 0, p.calls);
        assertTrue("and never leaves a draft behind", drafts.byContact(1, 10).isEmpty());
    }

    /* 6 ───────────── VOICE PARITY: a contact tone override reaches the wire
       through intentional compose exactly like VoicePromptProofTest pins for replies. */
    @Test public void contactVoiceOverrideReachesTheWireThroughIntentionalCompose() {
        contacts.put(Fakes.contact(1, "Ada"));
        contacts.put(Fakes.contact(2, "Bode"));
        messages.add(Fakes.msg(1, Direction.INCOMING, "so is 4pm still on?"));
        messages.add(Fakes.msg(2, Direction.INCOMING, "so is 4pm still on?"));
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("clarify draft");
        DraftService svc = service(new Fakes.GatewayFake(p));

        settings.put(null, "tone", "0");      // global: warm
        settings.put(1L, "tone", "2");        // Ada override: direct
        assertTrue(compose(svc, 1, ComposeKind.CLARIFY).ok);
        String sysAda = p.lastRequest.system;
        assertTrue("override phrase wins on the wire",
            sysAda.contains(StyleControls.TONE.phrase(2)));
        assertFalse(sysAda.contains(StyleControls.TONE.phrase(0)));

        assertTrue(compose(svc, 2, ComposeKind.CLARIFY).ok);
        String sysBode = p.lastRequest.system;
        assertTrue("global setting still governs other contacts",
            sysBode.contains(StyleControls.TONE.phrase(0)));
        assertFalse("no override leakage through compose either",
            sysBode.contains(StyleControls.TONE.phrase(2)));
    }

    /* 7 ───────────── LEARNING PARITY: matured learned hints ride compose past
       the same 3-signal gate, and the raw FEEDBACK counters are credited in the
       audit exactly like the reply path (P-intelligence-2 contract). */
    @Test public void learningAndFeedbackCountersReachIntentionalComposeLikeAReply() {
        contacts.put(Fakes.contact(1, "Ada"));
        for (int i = 0; i < 3; i++) {   // LearningEngine.MIN_SIGNALS = 3
            learning.record(contacts.get(1), StyleSignal.Kind.EDITED, "shorter", null);
        }
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("opener draft");
        DraftService svc = service(new Fakes.GatewayFake(p));

        assertTrue(compose(svc, 1, ComposeKind.OPENER).ok);
        assertTrue("a matured learned hint is on the wire for an intentional draft",
            p.lastRequest.system.contains("keep replies noticeably shorter"));
        String snap = drafts.byContact(1, 10).get(0).promptSnapshotJson;
        assertTrue("the FEEDBACK evidence trail is credited on intentional drafts too",
            snap.contains("feedback so far for Ada"));
        assertTrue(snap.contains("3 edited"));
    }

    /* 8 ───────────── ONE INTENTION, ONE CURRENT DRAFT: a second compose purges
       the first unsaved draft — the approve/copy flow always sees exactly one. */
    @Test public void aSecondIntentionReplacesTheFirstUnsavedDraft() {
        contacts.put(Fakes.contact(1, "Ada"));
        messages.add(Fakes.msg(1, Direction.INCOMING, "let me check my schedule"));
        messages.add(Fakes.msg(1, Direction.OUTGOING, "did the landlord call back?"));
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("bump one", "bump two");
        DraftService svc = service(new Fakes.GatewayFake(p));

        assertTrue(compose(svc, 1, ComposeKind.FOLLOW_UP).ok);
        assertEquals(1, drafts.byContact(1, 10).size());

        Result<DraftOutcome> r2 = compose(svc, 1, ComposeKind.FOLLOW_UP);
        assertTrue(String.valueOf(r2.ok ? "" : r2.error), r2.ok);
        assertEquals("the stale intention is purged — always one current draft",
            1, drafts.byContact(1, 10).size());
        assertTrue(r2.value.drafts.get(0).promptSnapshotJson
            .contains("compose replaced 1 unsaved draft"));
    }

    /* 9 ───────────── DELEGATION: ComposeKind.REPLY through composeForContact IS
       the classic reply path — anchored on their message like any reply. */
    @Test public void replyKindDelegatesToTheClassicAnchoredReplyPath() {
        contacts.put(Fakes.contact(1, "Ada"));
        messages.add(Fakes.msg(1, Direction.INCOMING, "you still coming tonight?"));
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("plain reply");
        DraftService svc = service(new Fakes.GatewayFake(p));

        Result<DraftOutcome> r = compose(svc, 1, ComposeKind.REPLY);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        Draft d = r.value.drafts.get(0);
        assertNotNull("ComposeKind.REPLY is a real reply — anchored on their message",
            d.inReplyToId);
        assertFalse("and not labeled with any intentional kind",
            d.promptSnapshotJson.contains("compose:"));
    }
}
