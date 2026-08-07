package com.replymate.core.usecase;

import com.replymate.core.model.Contact;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Draft;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class DraftServiceTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.StyleStoreFake styles;
    private Fakes.DraftStoreFake drafts;
    private Fakes.UsageStoreFake usage;
    private Fakes.KvStoreFake kv;
    private ProfileService profiles;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        styles = new Fakes.StyleStoreFake();
        drafts = new Fakes.DraftStoreFake();
        usage = new Fakes.UsageStoreFake();
        kv = new Fakes.KvStoreFake();
        profiles = new ProfileService(kv);

        Contact a = Fakes.contact(1, "Amara");
        a.relationshipType = "close friend";
        contacts.put(a);
        contacts.put(Fakes.contact(2, "Bank Client"));

        messages.add(Fakes.msg(1, Direction.OUTGOING, "did you land safe?"));
        messages.add(Fakes.msg(1, Direction.INCOMING, "yes o! just dey settle"));
        messages.add(Fakes.msg(2, Direction.INCOMING, "B-SECRET: invoice is overdue"));
    }

    private Fakes.StyleSettingStoreFake styleSettings;
    private Fakes.LearningStoreFake learningStore;
    private Fakes.KvStoreFake learnKv;

    private DraftService service(Fakes.GatewayFake gateway) {
        styleSettings = new Fakes.StyleSettingStoreFake();
        learningStore = new Fakes.LearningStoreFake();
        learnKv = new Fakes.KvStoreFake();
        com.replymate.core.learning.LearningService learning =
            Fakes.learningService(learningStore, learnKv);
        // P-memory-audit: a real (empty-seeded) memory service over fakes.
        return new DraftService(contacts, messages, styles, profiles,
            drafts, usage, gateway, Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG,
            Fakes.styleService(styleSettings, learning), learning,
            new com.replymate.core.memory.MemoryService(
                new Fakes.MemoryStoreFake(), messages, kv, Fakes.FIXED_CLOCK));
    }

    @Test public void happyPathGeneratesPersistsVariantsAndUsage() {
        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("nice one!", "great to hear");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(provider)).generateForContact(1);

        assertTrue(r.ok);
        assertEquals(2, r.value.drafts.size());
        assertEquals(2, drafts.saved.size());
        assertEquals(r.value.drafts.get(0).variantGroup, r.value.drafts.get(1).variantGroup);
        assertEquals("test-model", drafts.saved.get(0).model);   // gateway display name recorded
        assertEquals(1, usage.events.size());
        assertEquals(11, usage.events.get(0).tokensIn);
        assertEquals(7, usage.events.get(0).tokensOut);
        assertEquals(1, provider.calls);

        // snapshot is a real audit of what was sent, scoped to THIS contact
        String snap = drafts.saved.get(0).promptSnapshotJson;
        assertTrue(snap.contains("just dey settle"));          // A's message present
        assertTrue(snap.contains("Amara: yes o! just dey settle")); // name-prefix mapped
        assertFalse("isolation breach", snap.contains("B-SECRET"));  // B never leaks in
        assertTrue(provider.lastRequest.system.contains("Amara"));
        assertFalse(provider.lastRequest.system.contains("Bank Client"));
    }

    @Test public void p4VoiceAndWhyFlowIntoPromptAndAudit() {
        DraftService svc = service(new Fakes.GatewayFake(
            Fakes.FakeProvider.returning("nice one!")));
        // global: direct tone + plenty emoji; contact override: no emoji + custom prompt
        styleSettings.put(null, "tone", "2");
        styleSettings.put(null, "emoji", "2");
        styleSettings.put(1L, "emoji", "0");
        styleSettings.put(1L, "custom.prompt", "B-SECRET custom line only for Amara");

        com.replymate.core.util.Result<DraftOutcome> r = svc.generateForContact(1);
        assertTrue(r.ok);
        String sys = drafts.saved.get(0).promptSnapshotJson;
        assertTrue(sys.contains("direct and to the point"));       // global base voice
        assertTrue(sys.contains("no emoji"));                      // contact override won
        assertFalse(sys.contains("plenty of emoji"));
        assertTrue(sys.contains("B-SECRET custom line"));
        assertTrue(sys.contains("\"why\":["));                      // audit trail stored
        assertTrue(sys.contains("contact override"));
    }

    @Test public void blocksWithoutConfiguredProvider() {
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(null)).generateForContact(1);
        assertFalse(r.ok);
        assertTrue(r.error.toLowerCase().contains("api key"));
        assertEquals(0, drafts.saved.size());
    }

    @Test public void failsClosedForPrivateContact() {
        Contact p = Fakes.contact(9, "Secret");
        p.privateMode = true;
        p.aiEnabled = false;
        contacts.put(p);
        messages.add(Fakes.msg(9, Direction.INCOMING, "hi"));
        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("x");
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(provider)).generateForContact(9);
        assertFalse(r.ok);
        assertTrue(r.error.toLowerCase().contains("private"));
        assertEquals("provider must never be called for private contact", 0, provider.calls);
    }

    @Test public void requiresAnIncomingMessage() {
        messages.deleteByContact(1);
        messages.add(Fakes.msg(1, Direction.OUTGOING, "only mine here"));
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(
            Fakes.FakeProvider.returning("x"))).generateForContact(1);
        assertFalse(r.ok);
        assertTrue(r.error.contains("message from Amara"));
    }

    @Test public void propagatesProviderErrorAndWritesNothing() {
        Result<DraftOutcome> r = service(new Fakes.GatewayFake(
            Fakes.FakeProvider.failing("QUOTA — Busy or daily limit reached"))).generateForContact(1);
        assertFalse(r.ok);
        assertTrue(r.error.contains("QUOTA"));
        assertEquals(0, drafts.saved.size());
        assertEquals(0, usage.events.size());
    }

    @Test public void unknownContactErrors() {
        assertFalse(service(new Fakes.GatewayFake(
            Fakes.FakeProvider.returning("x"))).generateForContact(999).ok);
    }

    /* ----------------- P-ux-fix: regenerate REPLACES unsaved drafts ----------------- */

    @Test public void regenerateReplacesUnsavedDrafts_KeepsStarredAndUsed() {
        Fakes.FakeProvider provider = Fakes.FakeProvider.returning("take one");
        DraftService svc = service(new Fakes.GatewayFake(provider));
        Result<DraftOutcome> r1 = svc.generateForContact(1);
        assertTrue(r1.ok);
        assertEquals(1, drafts.saved.size());
        long firstId = drafts.saved.get(0).id;

        // regenerate → the untouched draft is REPLACED, not duplicated
        Result<DraftOutcome> r2 = svc.generateForContact(1);
        assertTrue(r2.ok);
        assertEquals("old card replaced, new card in place", 1, drafts.saved.size());
        assertNotEquals(firstId, drafts.saved.get(0).id);

        // star the current draft + copy-mark another → both survive the next regen
        drafts.saved.get(0).favorite = true;
        com.replymate.core.model.Draft used = new com.replymate.core.model.Draft();
        used.contactId = 1;
        used.replyText = "already copied one";
        used.status = com.replymate.core.model.DraftStatus.COPIED;
        drafts.insert(used);

        Result<DraftOutcome> r3 = svc.generateForContact(1);
        assertTrue(r3.ok);
        assertEquals("starred + used survive, fresh draft added", 3, drafts.saved.size());
        int favs = 0, copied = 0, untagged = 0;
        for (com.replymate.core.model.Draft d : drafts.saved) {
            if (d.favorite) favs++;
            if (d.status == com.replymate.core.model.DraftStatus.COPIED) copied++;
            if (d.status == com.replymate.core.model.DraftStatus.GENERATED && !d.favorite) untagged++;
        }
        assertEquals(1, favs);
        assertEquals(1, copied);
        assertEquals(1, untagged);
    }

    @Test public void failedRegenerateNeverWipesExistingDraft() {
        Fakes.FakeProvider good = Fakes.FakeProvider.returning("keep me");
        DraftService svc = service(new Fakes.GatewayFake(good));
        assertTrue(svc.generateForContact(1).ok);
        assertEquals(1, drafts.saved.size());

        Fakes.FakeProvider broken = Fakes.FakeProvider.failing("HTTP 500 boom");
        DraftService failing = service(new Fakes.GatewayFake(broken));
        Result<DraftOutcome> r = failing.generateForContact(1);
        assertFalse(r.ok);
        assertEquals("a failed regen leaves the current draft alone", 1, drafts.saved.size());
        assertEquals("keep me", drafts.saved.get(0).replyText);
    }
}
