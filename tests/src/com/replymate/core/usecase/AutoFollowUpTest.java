package com.replymate.core.usecase;

import com.replymate.core.learning.LearningService;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.model.DraftStatus;
import com.replymate.core.model.StyleSignal;
import com.replymate.core.prompt.ComposeKind;
import com.replymate.core.style.StyleSettings;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-14, topic 3 (owner mandate): AUTO FOLLOW-UP after an approved
 *  reply. Per-contact, OFF by default, and it must KNOW WHEN NOT TO FOLLOW UP —
 *  so every skip case is pinned through the real {@code maybePrepareFollowUp}
 *  entry point: control off, private/AI-off contacts, their fresh message, a
 *  draft already waiting, bump-on-a-bump, once-per-reply, and the one PREPARE
 *  path, which must quote the approved reply text (a quick-reply send never
 *  lands in our store) and land as an approve-first draft. Never auto-sends. */
public final class AutoFollowUpTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.DraftStoreFake drafts;
    private Fakes.UsageStoreFake usage;
    private Fakes.KvStoreFake kv;
    private Fakes.KvStoreFake liveKv;
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
        liveKv = new Fakes.KvStoreFake();
        profiles = new ProfileService(kv);
        settings = new Fakes.StyleSettingStoreFake();
        learningStore = new Fakes.LearningStoreFake();
        learning = Fakes.learningService(learningStore, new Fakes.KvStoreFake());
        memory = new com.replymate.core.memory.MemoryService(
            new Fakes.MemoryStoreFake(), messages, kv, Fakes.FIXED_CLOCK);
    }

    private DraftService service(Fakes.GatewayFake gateway) {
        DraftService ds = new DraftService(contacts, messages, new Fakes.StyleStoreFake(),
            profiles, drafts, usage, gateway, Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(settings, learning), learning, memory);
        ds.setLiveKv(liveKv);
        return ds;
    }

    private void enable(long contactId) {
        settings.put(Long.valueOf(contactId), StyleSettings.AUTO_FOLLOW_KEY, "1");
    }

    /** A reply draft approved exactly as generated (the COPIED approval path). */
    private Draft approvedReply(DraftService svc, Fakes.FakeProvider p, long contactId,
                                String incoming) {
        messages.add(Fakes.msg(contactId, Direction.INCOMING, incoming));
        Result<DraftOutcome> g = svc.generateForContact(contactId);
        assertTrue(String.valueOf(g.ok ? "" : g.error), g.ok);
        Draft d = g.value.drafts.get(0);
        p.lastRequest = null;   // isolate the follow-up request that comes next
        drafts.updateStatus(d.id, DraftStatus.COPIED);
        return d;
    }

    /* 1 ─ OFF BY DEFAULT: absent setting ⇒ the policy refuses and NOTHING runs. */
    @Test public void offByDefaultPreparesNothing() {
        contacts.put(Fakes.contact(1, "Ada"));
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));
        Draft d = approvedReply(svc, p, 1, "you there?");

        int callsBefore = p.calls;
        Result<DraftOutcome> r = svc.maybePrepareFollowUp(1, d);
        assertFalse(r.ok);
        assertTrue(r.error.contains(FollowUpPolicy.REASON_OFF));
        assertEquals("the provider is never touched when the control is off",
            callsBefore, p.calls);
        assertTrue(drafts.byContact(1, 10).size() == 1);   // only the approved reply
    }

    /* 2 ─ THE PREPARE PATH: quick-reply-send reality — the approved reply never
       landed in our store, yet the follow-up must quote IT, not stale history. */
    @Test public void preparesAFollowUpQuotingTheApprovedReplyItself() {
        contacts.put(Fakes.contact(1, "Ada"));
        enable(1);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));
        Draft d = approvedReply(svc, p, 1, "are you still coming tonight?");

        Result<DraftOutcome> r = svc.maybePrepareFollowUp(1, d);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        String task = p.lastRequest.task.text;
        assertTrue("the task names the unanswered state", task.contains("still UNANSWERED by Ada"));
        assertTrue("the bump quotes the APPROVED reply text (never stored in-app)",
            task.contains(d.replyText));
        Draft f = r.value.drafts.get(0);
        assertNull(f.inReplyToId);
        assertTrue(f.promptSnapshotJson.contains("compose:follow_up"));
        assertEquals("still just a draft — the owner approves it like any other",
            DraftStatus.GENERATED, f.status);
        assertEquals("the once-per-reply anchor is persisted",
            String.valueOf(d.inReplyToId.longValue()),
            liveKv.get("followup.auto.anchor.1", ""));
    }

    /* 3 ─ THEIR TURN: a newer incoming than the one just answered ⇒ replying, not
       bumping, is the right move — and the policy says so, with zero provider cost. */
    @Test public void freshMessageAfterTheApprovalBlocksTheFollowUp() {
        contacts.put(Fakes.contact(1, "Ada"));
        enable(1);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));
        Draft d = approvedReply(svc, p, 1, "did you eat?");
        messages.add(Fakes.msg(1, Direction.INCOMING, "and bring water when coming"));

        int callsBefore = p.calls;
        Result<DraftOutcome> r = svc.maybePrepareFollowUp(1, d);
        assertFalse(r.ok);
        assertTrue(r.error.contains(FollowUpPolicy.REASON_THEIR_TURN));
        assertEquals(callsBefore, p.calls);
        assertTrue("no follow-up draft is left behind", drafts.byContact(1, 10).size() == 1);
    }

    /* 4 ─ A WAITING DRAFT blocks: never queue a distraction on top of one. */
    @Test public void aWaitingDraftBlocksTheFollowUp() {
        contacts.put(Fakes.contact(1, "Ada"));
        enable(1);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));
        messages.add(Fakes.msg(1, Direction.INCOMING, "you there?"));
        assertTrue(svc.generateForContact(1).ok);      // a GENERATED reply draft waits
        Draft approved = new Draft();                  // a manual-type approval signal:
        approved.inReplyToId = null;                   // no answered anchor…
        messages.add(Fakes.msg(1, Direction.OUTGOING, "sent by hand in WhatsApp"));
        // …so the anchor would be the outgoing — but the waiting draft must veto first
        int callsBefore = p.calls;
        Result<DraftOutcome> r = svc.maybePrepareFollowUp(1, approved);
        assertFalse(r.ok);
        assertTrue(r.error.contains(FollowUpPolicy.REASON_DRAFT_WAITING));
        assertEquals(callsBefore, p.calls);
    }

    /* 5 ─ BUMP ON A BUMP: approving an intentional draft arms nothing. */
    @Test public void followingUpOnAFollowUpIsBlockedByConstruction() {
        contacts.put(Fakes.contact(1, "Ada"));
        enable(1);
        messages.add(Fakes.msg(1, Direction.INCOMING, "let me check"));
        messages.add(Fakes.msg(1, Direction.OUTGOING, "did the landlord call?"));
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));
        Result<DraftOutcome> g = svc.composeForContact(1, ComposeKind.FOLLOW_UP);
        assertTrue(String.valueOf(g.ok ? "" : g.error), g.ok);
        Draft bump = g.value.drafts.get(0);
        drafts.updateStatus(bump.id, DraftStatus.COPIED);

        int callsBefore = p.calls;
        Result<DraftOutcome> r = svc.maybePrepareFollowUp(1, bump);
        assertFalse(r.ok);
        assertTrue(r.error.contains(FollowUpPolicy.REASON_BUMP_ON_BUMP));
        assertEquals(callsBefore, p.calls);
    }

    /* 6 ─ ONCE PER REPLY: re-approving the same draft (copy again) prepares nothing. */
    @Test public void oneFollowUpPerApprovedReplyEver() {
        contacts.put(Fakes.contact(1, "Ada"));
        enable(1);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));
        Draft d = approvedReply(svc, p, 1, "send the address");
        assertTrue(svc.maybePrepareFollowUp(1, d).ok);
        int callsAfterFirst = p.calls;

        // the follow-up got actioned… then the owner copies the SAME reply again
        Draft f = drafts.byContact(1, 10).get(0);      // newest = the follow-up
        drafts.updateStatus(f.id, DraftStatus.COPIED);
        Result<DraftOutcome> r = svc.maybePrepareFollowUp(1, d);
        assertFalse(r.ok);
        assertTrue(r.error.contains(FollowUpPolicy.REASON_ONCE_PER_REPLY));
        assertEquals("no second generation for the same reply", callsAfterFirst, p.calls);
    }

    /* 7 ─ RE-ARM ON A NEW REPLY: one-per-reply is not a permanent mute. */
    @Test public void aNewApprovedReplyReArmsTheFollowUp() {
        contacts.put(Fakes.contact(1, "Ada"));
        enable(1);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));
        Draft d1 = approvedReply(svc, p, 1, "are you home?");
        assertTrue(svc.maybePrepareFollowUp(1, d1).ok);
        drafts.updateStatus(drafts.byContact(1, 10).get(0).id, DraftStatus.COPIED);

        // a genuinely NEW unanswered message, answered by a NEW approved reply
        Draft d2 = approvedReply(svc, p, 1, "did you reach safely?");
        Result<DraftOutcome> r = svc.maybePrepareFollowUp(1, d2);
        assertTrue(String.valueOf(r.ok ? "" : r.error), r.ok);
        assertTrue("the new follow-up quotes the NEW approved reply",
            p.lastRequest.task.text.contains(d2.replyText));
    }

    /* 8 ─ GATES: private and AI-disabled contacts never auto-prepare. */
    @Test public void privateAndAiDisabledContactsAreAlwaysSkipped() {
        contacts.put(Fakes.contact(1, "Ada"));
        contacts.get(1).privateMode = true;
        contacts.put(Fakes.contact(2, "Bode"));
        contacts.get(2).aiEnabled = false;
        enable(1);
        enable(2);
        Fakes.FakeProvider p = Fakes.FakeProvider.returning("ok");
        DraftService svc = service(new Fakes.GatewayFake(p));
        Draft approved = new Draft();
        approved.inReplyToId = null;
        messages.add(Fakes.msg(1, Direction.OUTGOING, "manual send"));
        messages.add(Fakes.msg(2, Direction.OUTGOING, "manual send"));

        assertTrue(svc.maybePrepareFollowUp(1, approved).error
            .contains(FollowUpPolicy.REASON_PRIVATE));
        assertTrue(svc.maybePrepareFollowUp(2, approved).error
            .contains(FollowUpPolicy.REASON_AI_OFF));
        assertEquals(0, p.calls);
    }
}
