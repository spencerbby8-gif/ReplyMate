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

    /* ----------------- P-ux-fix: name-match attach + fork heal ----------------- */

    private static ContactMerger newMerger(Fakes.ContactStoreFake contacts,
            Fakes.MessageStoreFake messages, Fakes.DraftStoreFake drafts,
            Fakes.LearningStoreFake learning, Fakes.StyleSettingStoreFake settings,
            Fakes.MemoryStoreFake memory, Fakes.KvStoreFake kv) {
        return new ContactMerger(contacts, messages, drafts, learning, settings,
            memory, kv, Fakes.FIXED_CLOCK);
    }

    @Test public void whatsappChatAttachesToManualContactWithSameName() {
        Fakes.ContactStoreFake store = new Fakes.ContactStoreFake();
        ContactService svc = new ContactService(store, Fakes.FIXED_CLOCK);
        Contact manual = svc.createManualContact("Amara", "", "", "", "").value;

        Contact wa = svc.ensureChannelContact(Channel.WHATSAPP, "amara", "Amara");

        assertEquals("same person = ONE chat, no duplicate", manual.id, wa.id);
        assertEquals(1, store.all().size());
        // the WhatsApp key got LINKED to the manual contact for future direct hits
        assertEquals(manual.id,
            store.findChannel(Channel.WHATSAPP, "amara").contactId);
        // …and a second notification updates that same chat
        Contact again = svc.ensureChannelContact(Channel.WHATSAPP, "amara", "Amara");
        assertEquals(manual.id, again.id);
        assertEquals(1, store.all().size());
    }

    @Test public void differentNamesNeverMerge() {
        Fakes.ContactStoreFake store = new Fakes.ContactStoreFake();
        ContactService svc = new ContactService(store, Fakes.FIXED_CLOCK);
        Contact a = svc.ensureChannelContact(Channel.WHATSAPP, "amara", "Amara");
        Contact b = svc.ensureChannelContact(Channel.WHATSAPP, "kunle", "Kunle");
        assertNotEquals(a.id, b.id);
        assertEquals(2, store.all().size());
    }

    @Test public void placeholderNamesNeverMatch() {
        Fakes.ContactStoreFake store = new Fakes.ContactStoreFake();
        ContactService svc = new ContactService(store, Fakes.FIXED_CLOCK);
        Contact a = svc.ensureChannelContact(Channel.WHATSAPP, "k1", "");
        Contact b = svc.ensureChannelContact(Channel.WHATSAPP, "k2", null);
        assertNotEquals("two untitled chats must stay separate", a.id, b.id);
        assertEquals(2, store.all().size());
    }

    @Test public void sameGroupTitleOnDifferentAppsStaysSeparate() {
        Fakes.ContactStoreFake store = new Fakes.ContactStoreFake();
        ContactService svc = new ContactService(store, Fakes.FIXED_CLOCK);
        Contact wa = svc.ensureChannelContact(Channel.WHATSAPP, "the crew", "The Crew");
        Contact dc = svc.ensureChannelContact(Channel.DISCORD, "the crew", "The Crew");
        assertNotEquals("a WhatsApp group is not the Discord server of the same name",
            wa.id, dc.id);
    }

    @Test public void preExistingForkHealsIntoOneChat_MovingAllData() {
        Fakes.ContactStoreFake contacts = new Fakes.ContactStoreFake();
        Fakes.MessageStoreFake messages = new Fakes.MessageStoreFake();
        Fakes.DraftStoreFake drafts = new Fakes.DraftStoreFake();
        Fakes.LearningStoreFake learning = new Fakes.LearningStoreFake();
        Fakes.StyleSettingStoreFake settings = new Fakes.StyleSettingStoreFake();
        Fakes.MemoryStoreFake memory = new Fakes.MemoryStoreFake();
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        ContactService svc = new ContactService(contacts, Fakes.FIXED_CLOCK);
        svc.setMerger(newMerger(contacts, messages, drafts, learning, settings, memory, kv));

        // the REAL chat (created when the first WhatsApp notification arrived)
        Contact keep = svc.ensureChannelContact(Channel.WHATSAPP, "amara", "Amara");

        // a stale fork from before name-matching existed (same person, own key)
        Contact dup = new Contact();
        dup.displayName = "Amara";
        contacts.insert(dup);
        com.replymate.core.model.ContactChannel ch = new com.replymate.core.model.ContactChannel();
        ch.contactId = dup.id;
        ch.channel = Channel.WHATSAPP;
        ch.remoteKey = "cid:999@s.whatsapp.net";
        contacts.upsertChannel(ch);
        // …with its own thread, draft, fact and style signal
        com.replymate.core.model.Message m = new com.replymate.core.model.Message();
        m.contactId = dup.id;
        m.channel = Channel.WHATSAPP;
        m.direction = com.replymate.core.model.Direction.INCOMING;
        m.body = "message trapped in the fork";
        m.sentAt = 1;
        m.source = com.replymate.core.model.Source.LISTENER;
        messages.add(m);
        com.replymate.core.model.Draft d = new com.replymate.core.model.Draft();
        d.contactId = dup.id;
        d.replyText = "draft trapped in the fork";
        drafts.insert(d);
        com.replymate.core.model.MemoryFact f = new com.replymate.core.model.MemoryFact();
        f.contactId = dup.id;
        f.text = "allergic to peanuts";
        f.textNorm = "allergic to peanuts";
        memory.upsertFact(f);
        com.replymate.core.model.StyleSignal sig = new com.replymate.core.model.StyleSignal();
        sig.contactId = dup.id;
        sig.kind = com.replymate.core.model.StyleSignal.Kind.APPROVED;
        learning.insert(sig);
        settings.put(dup.id, "tone", "0");
        kv.put(com.replymate.core.memory.MemoryService.styleKey(dup.id), "stale-style");
        assertEquals(2, contacts.all().size());

        // next notification for the real chat heals the fork
        Contact again = svc.ensureChannelContact(Channel.WHATSAPP, "amara", "Amara");

        assertEquals(keep.id, again.id);
        assertEquals("fork merged away", 1, contacts.all().size());
        assertNull("dup contact deleted", contacts.get(dup.id));
        assertEquals("thread moved", 1, messages.byContact.get(keep.id).size());
        assertEquals("message trapped in the fork",
            messages.byContact.get(keep.id).get(0).body);
        assertEquals("draft moved", keep.id, drafts.saved.get(0).contactId);
        assertEquals("fact moved", 1, memory.activeFacts(keep.id).size());
        assertEquals("signal moved", 1, learning.byContact(keep.id, 10).size());
        assertEquals("style override moved", "0", settings.all(keep.id).get("tone"));
        assertEquals("channel key re-pointed", keep.id,
            contacts.findChannel(Channel.WHATSAPP, "cid:999@s.whatsapp.net").contactId);
        assertFalse("stale learned-style cache dropped",
            kv.contains(com.replymate.core.memory.MemoryService.styleKey(dup.id)));
    }

    @Test public void mergeCollisions_keptContactsValuesWin() {
        Fakes.ContactStoreFake contacts = new Fakes.ContactStoreFake();
        Fakes.MessageStoreFake messages = new Fakes.MessageStoreFake();
        Fakes.DraftStoreFake drafts = new Fakes.DraftStoreFake();
        Fakes.LearningStoreFake learning = new Fakes.LearningStoreFake();
        Fakes.StyleSettingStoreFake settings = new Fakes.StyleSettingStoreFake();
        Fakes.MemoryStoreFake memory = new Fakes.MemoryStoreFake();
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        ContactMerger merger = newMerger(contacts, messages, drafts, learning, settings, memory, kv);

        Contact keep = Fakes.contact(1, "Amara"); contacts.put(keep);
        Contact dup = Fakes.contact(2, "amara ");  contacts.put(dup);
        settings.put(1L, "tone", "2");     // kept: direct
        settings.put(2L, "tone", "0");     // dup: warm  → collision, kept wins
        settings.put(2L, "emoji", "2");    // dup-only key → moves
        com.replymate.core.model.MemoryFact fk = new com.replymate.core.model.MemoryFact();
        fk.contactId = 1; fk.text = "likes pepper soup"; fk.textNorm = "likes pepper soup";
        memory.upsertFact(fk);
        com.replymate.core.model.MemoryFact fd = new com.replymate.core.model.MemoryFact();
        fd.contactId = 2; fd.text = "LIKES PEPPER SOUP"; fd.textNorm = "likes pepper soup";
        memory.upsertFact(fd);
        com.replymate.core.model.ContactChannel ch = new com.replymate.core.model.ContactChannel();
        ch.contactId = 2; ch.channel = Channel.WHATSAPP; ch.remoteKey = "same-key";
        contacts.upsertChannel(ch);
        contacts.insert(Fakes.contact(3, "Somebody Else"));   // untouched bystander

        merger.mergeInto(1, 2);

        assertEquals("kept contact's tone wins", "2", settings.all(1L).get("tone"));
        assertEquals("dup-only key adopted", "2", settings.all(1L).get("emoji"));
        assertTrue(settings.all(2L).isEmpty());
        assertEquals("fact text_norm collision deduped", 1, memory.allFacts(1).size());
        assertEquals("likes pepper soup", memory.allFacts(1).get(0).text);
        assertNull(contacts.get(2));
        assertNotNull("bystander contact untouched", contacts.get(3));
    }
}
