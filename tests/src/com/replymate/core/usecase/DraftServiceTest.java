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

    private DraftService service(Fakes.GatewayFake gateway) {
        return new DraftService(contacts, messages, styles, profiles,
            drafts, usage, gateway, Fakes.IDS, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG);
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
}
