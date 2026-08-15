package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.usecase.ContactService;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-15, topic 2: MessagingStyle HISTORY + GROUP FACT capture.\n *
 *  - Historic messages (the documented "android.messages.historic" extra,
 *    getHistoricMessages(), API 26+) are parsed as CONTEXT: stored, deduped,
 *    NEVER pinged (they are grounding for the burst, not new events).
 *  - isGroupConversation is persisted on the contact (schema v7) at ingest —
 *    the group fact is capture evidence, never a downstream guess. */
public final class GroupHistoryTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.KvStoreFake kv;
    private IngestCoordinator engine;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        kv = new Fakes.KvStoreFake();
        engine = new IngestCoordinator(
            new ContactService(contacts, Fakes.FIXED_CLOCK),
            messages, kv, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG);
    }

    private static RawNotif.Entry entry(String sender, String text, long ts) {
        RawNotif.Entry e = new RawNotif.Entry();
        e.senderName = sender;
        e.text = text;
        e.timestampMs = ts;
        return e;
    }

    /* 1 ─ parser: historic entries flow as flagged context, live order preserved. */
    @Test public void historicMessagesParseAsContextAheadOfLiveOnes() {
        RawNotif raw = new RawNotif();
        raw.packageName = "com.whatsapp";
        raw.category = "msg";
        raw.convTitle = "Family group";
        raw.group = Boolean.TRUE;
        raw.postTimeMs = 2000;
        raw.historic.add(entry("Musa", "meeting moved to 3pm", 900));
        raw.historic.add(entry("Chidi", "noted o", 1000));
        raw.messages.add(entry("Musa", "so who is coming?", 2000));

        NotifParser.Result r = new MessagingStyleParser(Channel.WHATSAPP).parse(raw);
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);
        assertEquals(3, r.events.size());
        assertTrue("historic rides first, flagged", r.events.get(0).historic);
        assertTrue(r.events.get(1).historic);
        assertFalse("the live message is NOT context", r.events.get(2).historic);
        assertEquals("earlier context order is preserved", "meeting moved to 3pm",
            r.events.get(0).text);
        assertEquals("Musa", r.events.get(0).senderName);
        assertEquals("so who is coming?", r.events.get(2).text);
    }

    /* 2 ─ parser: sender-less historic inserts inside a sendered payload drop the
       same way live system inserts do (encryption notices etc.). */
    @Test public void senderlessHistoricInsertsAreDroppedLikeLiveOnes() {
        RawNotif raw = new RawNotif();
        raw.packageName = "com.whatsapp";
        raw.category = "msg";
        raw.convTitle = "Ada";
        raw.group = Boolean.FALSE;
        raw.postTimeMs = 2000;
        raw.historic.add(entry(null, "messages you send to this chat are now secured", 800));
        raw.messages.add(entry("Ada", "you dey?", 2000));

        NotifParser.Result r = new MessagingStyleParser(Channel.WHATSAPP).parse(raw);
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);
        assertEquals("the bare system insert is dropped", 1, r.events.size());
        assertEquals("Ada", r.events.get(0).senderName);
    }

    /* 3 ─ ingest: historic context STORES but NEVER pings, and dedupes on re-post. */
    @Test public void historicStoresWithoutPingAndDedupes() {
        kv.put("groups.enabled", "1");   // groups are OPT-IN — enable the master switch
        List<NotifEvent> wave = new ArrayList<NotifEvent>();
        NotifEvent h = new NotifEvent();
        h.channel = Channel.WHATSAPP;
        h.conversationTitle = "Family group";
        h.senderName = "Musa";
        h.ownerName = "Me";
        h.text = "meeting moved to 3pm";
        h.timestampMs = 900;
        h.group = true;
        h.historic = true;
        wave.add(h);
        NotifEvent live = new NotifEvent();
        live.channel = Channel.WHATSAPP;
        live.conversationTitle = "Family group";
        live.senderName = "Chidi";
        live.ownerName = "Me";
        live.text = "who is coming?";
        live.timestampMs = 2000;
        live.group = true;
        wave.add(live);

        IngestReport rep = engine.handle(wave, null);
        assertEquals("historic + live both store", 2, rep.stored);
        assertEquals("only the LIVE message may ping", 1, rep.pings.size());
        assertEquals("who is coming?", rep.pings.get(0).snippet);

        IngestReport again = engine.handle(wave, null);
        assertEquals("a re-post dedupes both historic and live rows", 0, again.stored);
        assertEquals(2, again.duplicates);
    }

    /* 4 ─ the group FACT persists on the contact from capture evidence (v7). */
    @Test public void groupFlagPersistsFromCaptureEvidence() {
        List<NotifEvent> wave = new ArrayList<NotifEvent>();
        NotifEvent e = new NotifEvent();
        e.channel = Channel.WHATSAPP;
        e.conversationTitle = "Family group";
        e.senderName = "Musa";
        e.ownerName = "Me";
        e.text = "meeting moved to 3pm";
        e.timestampMs = 1000;
        e.group = true;
        wave.add(e);

        // groups need the opt-in: master on for this app
        kv.put("groups.enabled", "1");
        engine.handle(wave, null);

        Contact saved = null;
        for (Contact c : contacts.all()) saved = c;
        assertNotNull("the group conversation created its own contact", saved);
        assertTrue("isGroupConversation persisted (schema v7 fact)", saved.isGroup);
        assertEquals("Family group", saved.displayName);
    }

    @Test public void oneOnOneChatsStayNonGroup() {
        List<NotifEvent> wave = new ArrayList<NotifEvent>();
        NotifEvent e = new NotifEvent();
        e.channel = Channel.WHATSAPP;
        e.conversationTitle = "Ada";
        e.senderName = "Ada";
        e.ownerName = "Me";
        e.text = "you dey?";
        e.timestampMs = 1000;
        e.group = false;
        wave.add(e);

        engine.handle(wave, null);
        Contact saved = contacts.all().get(0);
        assertFalse(saved.isGroup);
    }
}
