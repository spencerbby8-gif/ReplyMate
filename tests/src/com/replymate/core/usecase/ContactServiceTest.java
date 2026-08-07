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

    /* ----------------- P-audit-deep: identity alias linking (no contact forks) ------- */

    @Test public void legacyTitledChatRelinksToNativeIdWithoutForking() {
        Fakes.ContactStoreFake store = new Fakes.ContactStoreFake();
        ContactService svc = new ContactService(store, Fakes.FIXED_CLOCK);
        // 1) contact created BEFORE the app published a native id (legacy title key)
        Contact first = svc.ensureChannelContact(Channel.WHATSAPP, "amara", "Amara");
        // 2) later the notification carries WhatsApp's own thread id → candidates
        java.util.List<String> keys = java.util.Arrays.asList(
            "cid:23480@s.whatsapp.net", "amara");
        Contact again = svc.ensureChannelContact(
            Channel.WHATSAPP, keys.get(0), "Amara", keys);
        assertEquals("same contact, no fork on identity upgrade", first.id, again.id);
        assertEquals(1, store.all().size());
        // the stronger key is LINKED for direct future hits
        assertNotNull(store.findChannel(Channel.WHATSAPP, "cid:23480@s.whatsapp.net"));
        assertNotNull(store.findChannel(Channel.WHATSAPP, "amara"));
        // 3) subsequent native-id-only notifications resolve straight to the contact
        Contact direct = svc.ensureChannelContact(
            Channel.WHATSAPP, "cid:23480@s.whatsapp.net", "Amara",
            java.util.Arrays.asList("cid:23480@s.whatsapp.net", "amara"));
        assertEquals(first.id, direct.id);
    }

    @Test public void differentNativeIdsStayDifferentContacts() {
        Fakes.ContactStoreFake store = new Fakes.ContactStoreFake();
        ContactService svc = new ContactService(store, Fakes.FIXED_CLOCK);
        java.util.List<String> aKeys = java.util.Arrays.asList("cid:a@x", "amara");
        java.util.List<String> bKeys = java.util.Arrays.asList("cid:b@x", "amara b");
        Contact a = svc.ensureChannelContact(Channel.WHATSAPP, aKeys.get(0), "Amara", aKeys);
        Contact b = svc.ensureChannelContact(Channel.WHATSAPP, bKeys.get(0), "Amara B", bKeys);
        assertNotEquals("two chats with similar names but different native ids must not merge",
            a.id, b.id);
    }
}
