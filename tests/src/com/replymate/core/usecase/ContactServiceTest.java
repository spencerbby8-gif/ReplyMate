package com.replymate.core.usecase;

import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.fakes.Fakes;
import org.junit.Test;
import static org.junit.Assert.*;

public class ContactServiceTest {

    @Test public void ensureCreatesOnceAndReusesAfterwards() {
        Fakes.ContactStoreFake store = new Fakes.ContactStoreFake();
        ContactService svc = new ContactService(store, Fakes.FIXED_CLOCK);

        Contact first = svc.ensureChannelContact(Channel.WHATSAPP, "amara", "Amara");
        assertEquals("Amara", first.displayName);
        assertEquals(1, store.all().size());

        Contact again = svc.ensureChannelContact(Channel.WHATSAPP, "amara", "Amara");
        assertEquals(first.id, again.id);
        assertEquals(1, store.all().size());
        assertEquals(1, store.channelsByContact(first.id).size());
    }

    @Test public void samePersonOnTwoChannelsGetsTwoChannelRowsOneContactEach() {
        Fakes.ContactStoreFake store = new Fakes.ContactStoreFake();
        ContactService svc = new ContactService(store, Fakes.FIXED_CLOCK);
        // P2 policy: identities are per-channel; merging arrives P3. This test pins that.
        Contact wa = svc.ensureChannelContact(Channel.WHATSAPP, "amara", "Amara");
        Contact tg = svc.ensureChannelContact(Channel.TELEGRAM, "amara", "Amara");
        assertNotEquals(wa.id, tg.id);
    }

    @Test public void privateModeDisablesAiInvariant() {
        Fakes.ContactStoreFake store = new Fakes.ContactStoreFake();
        ContactService svc = new ContactService(store, Fakes.FIXED_CLOCK);
        Contact c = Fakes.contact(5, "Secret");
        c.privateMode = true;
        c.aiEnabled = true;
        svc.update(c);
        assertFalse(store.get(5).aiEnabled);
    }

    @Test public void createManualRequiresName() {
        ContactService svc = new ContactService(new Fakes.ContactStoreFake(), Fakes.FIXED_CLOCK);
        assertFalse(svc.createManualContact("  ", "", "", "", "").ok);
        assertTrue(svc.createManualContact("Amara", "", "", "", "").ok);
    }
}
