package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import com.replymate.core.usecase.ContactService;
import com.replymate.fakes.Fakes;
import java.util.EnumSet;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P21-production §3: Discord's three conversation shapes get three honest answers.
 *
 *   1:1 DM         → processed as a private conversation (REAL_1TO1 path).
 *   Group DM       → group pipeline: sender identity preserved per message,
 *                    group context kept; default-OFF groups opt-in governs it.
 *   Server channel → SEPARATE category, OFF by default (ServerGate, no global
 *                    master). Opted-in: conversations flow; announcement cards
 *                    and bot/webhook broadcasts (no free-form reply affordance)
 *                    are store-only and can never ping or draft.
 *
 *  Drives the REAL stack (ParserRegistry → IngestCoordinator) like
 *  GroupOptInTest does. */
public final class DiscordCategoriesTest {

    private ParserRegistry registry;

    private static final class Run {
        ParserRegistry.Outcome out;
        IngestReport rep;
        Fakes.KvStoreFake kv;
        Fakes.ContactStoreFake contacts;
    }

    @Before public void setUp() { registry = new ParserRegistry(); }

    private Run drive(RawNotif raw, String... kvPairs) {
        Run r = new Run();
        r.kv = new Fakes.KvStoreFake();
        r.contacts = new Fakes.ContactStoreFake();
        Fakes.MessageStoreFake messages = new Fakes.MessageStoreFake();
        for (int i = 0; i + 1 < kvPairs.length; i += 2) r.kv.put(kvPairs[i], kvPairs[i + 1]);
        IngestCoordinator engine = new IngestCoordinator(
            new ContactService(r.contacts, Fakes.FIXED_CLOCK), messages, r.kv,
            Fakes.FIXED_CLOCK, Fakes.NOOP_LOG);
        r.out = registry.route(raw.packageName, raw, EnumSet.allOf(Channel.class),
            new ListenerStats(r.kv));
        if (r.out.kind == ParserRegistry.OutcomeKind.PARSED) {
            r.rep = engine.handle(r.out.events, null);
        }
        return r;
    }

    /* --------------------------------------------------------------- 1:1 DM */

    @Test public void oneToOneDmProcessesNormally() {
        Run r = drive(ParserFixtures.titleText("com.discord", "zee", "you up?", "msg"));
        assertEquals(ParserRegistry.OutcomeKind.PARSED, r.out.kind);
        assertNotNull(r.rep);
        assertEquals(1, r.rep.stored);
        assertEquals(1, r.rep.pings.size());               // a real DM pings/drafts
    }

    /* -------------------------------------------------------------- group DM */

    @Test public void groupDmKeepsSenderIdentityAndGroupContext() {
        // Discord conversation notification WITH MessagingStyle: per-sender truth
        RawNotif r = ParserFixtures.styleGroup("com.discord", "Weekend crew", "mira", "asoebi colors are in");
        Run out = drive(r, "watch.discord", "1", "groups.enabled.discord", "1");
        assertNotNull(out.rep);
        assertEquals(1, out.rep.stored);
        assertEquals(1, out.rep.pings.size());
        assertEquals(1, out.contacts.all().size());
        assertTrue("group-namespaced contact, not a 1:1 masquerade",
            out.contacts.all().get(0).displayName.contains("Weekend crew"));
    }

    @Test public void groupDmStaysOffByDefault() {
        RawNotif r = ParserFixtures.styleGroup("com.discord", "Weekend crew", "mira", "asoebi colors are in");
        Run out = drive(r, "watch.discord", "1");
        assertNotNull(out.rep);
        assertEquals(0, out.rep.stored);
        assertEquals(0, out.rep.pings.size());
    }

    /* --------------------------------------------------------- server channel */

    private RawNotif server(String channelTitle, String sender, String text,
                            boolean withReplyAction, boolean withHistory) {
        RawNotif r = ParserFixtures.raw("com.discord");
        r.title = channelTitle;
        r.convTitle = channelTitle;
        r.group = Boolean.TRUE;
        r.category = "msg";
        if (withHistory) {
            r.messages.add(ParserFixtures.msg(text, 1000L, sender, false));
        } else {
            r.text = sender + ": " + text;
        }
        if (withReplyAction) {
            RawNotif.ActionRef a = new RawNotif.ActionRef();
            a.title = "Reply";
            a.remoteFreeForm = true;
            a.resultKey = "key_text_reply";
            r.actions.add(a);
        }
        return r;
    }

    @Test public void serverChannelDroppedByDefault() {
        Run r = drive(server("#general", "zee", "gm gm", true, true),
            "watch.discord", "1", "groups.enabled.discord", "1");
        assertNotNull(r.rep);
        assertEquals("server channels are a separate OFF-BY-DEFAULT category — not groups",
            0, r.rep.stored);
        assertEquals(0, r.rep.pings.size());
    }

    @Test public void optedInServerConversationFlows() {
        Run r = drive(server("#general", "zee", "standup moved to 10", true, true),
            "watch.discord", "1", "servers.enabled.discord", "1");
        assertNotNull(r.rep);
        assertEquals(1, r.rep.stored);
        assertEquals("a replyable server conversation may ping when opted in",
            1, r.rep.pings.size());
    }

    @Test public void serverAnnouncementNeverPingsOrDrafts() {
        // self-announce shape (sender == channel title): ANNOUNCEMENT → store-only
        Run r = drive(server("#announcements", "#announcements", "new rules pinned", false, false),
            "watch.discord", "1", "servers.enabled.discord", "1");
        assertNotNull(r.rep);
        assertEquals(0, r.rep.pings.size());
    }

    @Test public void serverBotBroadcastNeverPingsOrDrafts() {
        // a bot/webhook post carries NO free-form reply affordance: capability
        // honesty demotes it to store-only inside an opted-in server channel
        Run r = drive(server("#general", "MEE6", "member joined — say hi!", false, true),
            "watch.discord", "1", "servers.enabled.discord", "1");
        assertNotNull(r.rep);
        assertEquals(0, r.rep.pings.size());
    }

    /* --------------------------------------------------------------- the gate */

    @Test public void serverGateKeysAndShapes() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        assertFalse("absent = off (fail-closed)", ServerGate.allowed(kv, Channel.DISCORD));
        kv.put(ServerGate.keyFor(Channel.DISCORD), "1");
        assertTrue(ServerGate.allowed(kv, Channel.DISCORD));
        assertFalse("WhatsApp has no server concept", ServerGate.allowed(kv, Channel.WHATSAPP));

        NotifEvent e = new NotifEvent();
        e.channel = Channel.DISCORD; e.group = true; e.conversationTitle = "#general";
        assertTrue(ServerGate.isServerEvent(e));
        e.conversationTitle = "Weekend crew";
        assertFalse("group DM without '#' is NOT a server channel", ServerGate.isServerEvent(e));
        e.group = false; e.conversationTitle = "#dm-weird-name";
        assertFalse("non-group is never a server channel", ServerGate.isServerEvent(e));
        e.group = true; e.channel = Channel.WHATSAPP; e.conversationTitle = "#chants";
        assertFalse("gate is Discord-scoped", ServerGate.isServerEvent(e));
    }
}
