package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import com.replymate.core.usecase.ContactService;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-background-10: END-TO-END noise proof. Every noise class the owner observed
 *  on-device is driven through the REAL full stack — ParserRegistry (real
 *  parsers from WatchedApps) → IngestCoordinator — and must die BEFORE any
 *  contact, any stored row and any ping.
 *
 *  "Zero provider calls" is pinned by construction: background generation jobs
 *  are produced EXCLUSIVELY from IngestReport.pings (RmNotificationListener calls
 *  AssistantRunner.schedule exactly once per ping, and the catch-up sweep only
 *  ever inspects STORED rows). A noise class that stores nothing and pings
 *  nothing can never reach a provider. Real 1:1 controls prove the gate is not
 *  blunt. */
public final class NoiseEndToEndTest {

    private ParserRegistry registry;

    private static final class Run {
        ParserRegistry.Outcome out;
        IngestReport rep;
        int contactsCreated;
        int messagesStored;
    }

    @Before public void setUp() {
        registry = new ParserRegistry();
    }

    /** Drive one raw notification through the real stack with fresh stores. */
    private Run drive(RawNotif raw) {
        Fakes.ContactStoreFake contacts = new Fakes.ContactStoreFake();
        Fakes.MessageStoreFake messages = new Fakes.MessageStoreFake();
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        IngestCoordinator engine = new IngestCoordinator(
            new ContactService(contacts, Fakes.FIXED_CLOCK), messages, kv,
            Fakes.FIXED_CLOCK, Fakes.NOOP_LOG);
        Run r = new Run();
        r.out = registry.route(raw.packageName, raw, EnumSet.allOf(Channel.class),
            new ListenerStats(kv));
        if (r.out.kind == ParserRegistry.OutcomeKind.PARSED) {
            r.rep = engine.handle(r.out.events, null);
            r.contactsCreated = contacts.all().size();
            r.messagesStored = 0;
            for (com.replymate.core.model.Contact c : contacts.all()) {
                r.messagesStored += messages.countByContact(c.id);
            }
        }
        return r;
    }

    private static void assertSilent(String what, Run r) {
        if (r.out.kind == ParserRegistry.OutcomeKind.PARSED) {
            assertNotNull(what + ": parsed noise must still produce a report", r.rep);
            assertEquals(what + ": stored rows", 0, r.rep.stored);
            assertEquals(what + ": pings (⇒ background jobs ⇒ provider calls)",
                0, r.rep.pings.size());
            assertEquals(what + ": contacts created", 0, r.contactsCreated);
            assertEquals(what + ": message rows", 0, r.messagesStored);
        }
    }

    private static RawNotif styleText(String pkg, String title, String conv,
                                      String sender, String text, long ts) {
        RawNotif raw = ParserFixtures.raw(pkg);
        raw.title = title;
        raw.convTitle = conv;
        raw.ownerName = "Me";
        raw.group = Boolean.FALSE;
        raw.messages.add(ParserFixtures.msg(text, ts, sender, false));
        return raw;
    }

    /* ------------------------------------------------------------ the battery */

    @Test public void everyObservedNoiseClassIsSilent() {
        List<String> names = new ArrayList<String>();
        List<RawNotif> battery = new ArrayList<RawNotif>();

        // 1. backup card (self-titled, ongoing housekeeping)
        RawNotif backup = ParserFixtures.raw("com.whatsapp");
        backup.title = "WhatsApp";
        backup.text = "Backing up messages…";
        backup.progressMax = 100;
        battery.add(backup); names.add("backup card");

        // 2. service-summary digest
        RawNotif digest = ParserFixtures.raw("com.whatsapp");
        digest.title = "WhatsApp";
        digest.text = "3 new messages";
        digest.category = "msg";
        battery.add(digest); names.add("summary digest");

        // 3. announcement channel post (WhatsApp Channels native id)
        RawNotif newsletter = styleText("com.whatsapp", "Naija Daily",
            "Naija Daily", "Naija Daily", "Fuel price update: …", 1000L);
        newsletter.conversationId = "120363123456789@newsletter";
        battery.add(newsletter); names.add("announcement channel (newsletter id)");

        // 4. broadcast-list / status surface (native id)
        RawNotif broadcast = styleText("com.whatsapp", "Family list",
            "Family list", "Family list", "Sunday rice at mine", 1000L);
        broadcast.conversationId = "2348012345678@broadcast";
        battery.add(broadcast); names.add("broadcast list (broadcast id)");

        // 5. the app's own service chat (1:1-shaped, app-labeled — WhatsApp/Telegram
        //    system notices arrive here)
        battery.add(styleText("com.whatsapp", "WhatsApp", "WhatsApp", "WhatsApp",
            "Your registration was checked on a new phone.", 1000L));
        names.add("in-app service chat");
        battery.add(styleText("org.telegram.messenger", "Telegram", "Telegram",
            "Telegram", "Your login code is 45231.", 1000L));
        names.add("service account (Telegram)");

        // 6. group conversation
        battery.add(ParserFixtures.styleGroup("com.whatsapp", "Market women",
            "Nkem", "who has the match tickets?"));
        names.add("group conversation");

        // 7. missed call WITH the call category
        RawNotif catCall = ParserFixtures.raw("com.whatsapp");
        catCall.title = "Amara";
        catCall.text = "Missed voice call";
        catCall.category = "call";
        battery.add(catCall); names.add("missed call (call category)");

        // 8. missed call WITHOUT any category, time-tailed card text
        RawNotif bareCall = ParserFixtures.raw("com.whatsapp");
        bareCall.title = "Amara";
        bareCall.text = "Missed voice call at 2:14 pm";
        bareCall.postTimeMs = 2000L;
        battery.add(bareCall); names.add("missed call (bare card text)");

        // 9. reaction-only notice
        RawNotif reaction = ParserFixtures.raw("com.whatsapp");
        reaction.title = "Amara";
        reaction.text = "Reacted 👍 to “see you tonight”";
        reaction.postTimeMs = 3000L;
        battery.add(reaction); names.add("reaction notice");

        // 10. media-only first contact (may never CREATE a conversation)
        RawNotif mediaOnly = ParserFixtures.raw("org.telegram.messenger");
        mediaOnly.title = "Sam";
        mediaOnly.messages.add(ParserFixtures.msg(null, 3000L, "Sam", true));
        battery.add(mediaOnly); names.add("media-only first contact");

        // 11. in-chat encryption system line (single-shot shape)
        RawNotif e2e = ParserFixtures.raw("com.whatsapp");
        e2e.title = "Amara";
        e2e.text = "🔒 Messages and calls are end-to-end encrypted. Tap to learn more.";
        e2e.postTimeMs = 4000L;
        battery.add(e2e); names.add("encryption system line");

        // 12. summary geometry under a contact title
        RawNotif geo = ParserFixtures.raw("com.whatsapp");
        geo.title = "Amara";
        geo.text = "2 new messages";
        geo.postTimeMs = 5000L;
        battery.add(geo); names.add("summary geometry under a title");

        assertEquals("battery size matches its names", battery.size(), names.size());
        for (int i = 0; i < battery.size(); i++) {
            assertSilent(names.get(i), drive(battery.get(i)));
        }
    }

    /** P-background-12: further REAL device-shade shapes reported during the
     *  blocking re-audit — including the layered ways the SAME housekeeping card
     *  can arrive (ongoing flag, category, phrase text, progress-less backup). */
    @Test public void newlyPlausibleNoiseShapesAreSilent() {
        List<String> names = new ArrayList<String>();
        List<RawNotif> battery = new ArrayList<RawNotif>();

        // 13. the transient connection card, verbatim from the device shade
        RawNotif checking = ParserFixtures.raw("com.whatsapp");
        checking.title = "WhatsApp";
        checking.text = "Checking for new messages";
        checking.ongoing = true;
        battery.add(checking); names.add("\"Checking for new messages\" (ongoing)");

        // 14. the same card in its non-ongoing variant (posted transiently)
        RawNotif checkingTransient = ParserFixtures.raw("com.whatsapp");
        checkingTransient.title = "WhatsApp";
        checkingTransient.text = "Checking for new messages…";
        battery.add(checkingTransient);
        names.add("\"Checking for new messages…\" (transient)");

        // 15. the companion-session card
        RawNotif web = ParserFixtures.raw("com.whatsapp");
        web.title = "WhatsApp";
        web.text = "WhatsApp Web is currently running";
        battery.add(web); names.add("\"WhatsApp Web is currently running\"");

        // 16. backup cards that carry NO progress bar — the category alone
        //     must indict them (MessagingStyleParser's category gate)
        RawNotif svc = ParserFixtures.raw("com.whatsapp");
        svc.title = "WhatsApp";
        svc.text = "Backing up messages…";
        svc.category = "service";
        battery.add(svc); names.add("backup card (service category, no progress)");
        RawNotif prog = ParserFixtures.raw("com.whatsapp");
        prog.title = "WhatsApp";
        prog.text = "Preparing Google Drive backup…";
        prog.category = "progress";
        battery.add(prog); names.add("backup card (progress category, no progressMax)");

        // 17. unread-count digest
        RawNotif unread = ParserFixtures.raw("com.whatsapp");
        unread.title = "WhatsApp";
        unread.text = "2 unread chats";
        battery.add(unread); names.add("\"2 unread chats\" digest");

        // 18. group WITHOUT a native conversation id — the API 24–28 shape,
        //     where the group flag alone must carry the drop
        RawNotif legacyGroup = ParserFixtures.styleGroup("com.whatsapp",
            "Devs hangout", "Emeka", "deploy finished ✅");
        legacyGroup.conversationId = null;
        battery.add(legacyGroup); names.add("group (API 24–28: no conversationId)");

        assertEquals("battery size matches its names", battery.size(), names.size());
        for (int i = 0; i < battery.size(); i++) {
            assertSilent(names.get(i), drive(battery.get(i)));
        }
    }

    /** P-background-12: the service-chat kill is LABEL-DRIVEN, so it must hold
     *  for EVERY watched app's label — not just the two cards seen on-device.
     *  The pin is deliberately meaningful: the card DOES parse (it is shaped
     *  exactly like a 1:1) and dies only inside the ingest gate. */
    @Test public void serviceChatLabelEqualityIsSilentForEveryWatchedApp() {
        for (WatchedApps.AppDef def : WatchedApps.all()) {
            RawNotif svcChat = ParserFixtures.raw(def.packageName);
            svcChat.title = def.label;
            svcChat.convTitle = def.label;
            svcChat.ownerName = "Me";
            svcChat.group = Boolean.FALSE;
            svcChat.text = "Your login code is 45231.";
            svcChat.messages.add(ParserFixtures.msg(
                "Your login code is 45231.", 1000L, def.label, false));
            Run r = drive(svcChat);
            assertEquals(def.packageName + " (" + def.label + "): a service-chat"
                + " card is 1:1-shaped, so it MUST parse and die at ingest",
                ParserRegistry.OutcomeKind.PARSED, r.out.kind);
            assertSilent(def.label + " service chat", r);
        }
    }

    /* -------------------------------------------------------------- controls */

    @Test public void realOneToOneStillFlowsToOnePing() {
        Run r = drive(ParserFixtures.styleDm("com.whatsapp", "Amara",
            ParserFixtures.T_GREET, ParserFixtures.T_FOLLOW));
        assertEquals(ParserRegistry.OutcomeKind.PARSED, r.out.kind);
        assertNotNull(r.rep);
        assertEquals(2, r.rep.stored);
        assertEquals(1, r.rep.pings.size());
        assertEquals(1, r.contactsCreated);
    }

    @Test public void aHumanSentenceAboutAMissedCallStillFlows() {
        RawNotif human = ParserFixtures.raw("com.whatsapp");
        human.title = "Amara";
        human.text = "sorry i missed your call 🙏";
        human.postTimeMs = 9000L;
        Run r = drive(human);
        assertEquals(ParserRegistry.OutcomeKind.PARSED, r.out.kind);
        assertEquals(1, r.rep.stored);
        assertEquals(1, r.rep.pings.size());
        assertEquals(1, r.contactsCreated);
    }

    @Test public void announcementDropLeavesAnHonestDiagnosticTrail() {
        RawNotif newsletter = styleText("com.whatsapp", "Naija Daily",
            "Naija Daily", "Naija Daily", "Evening headlines", 1000L);
        newsletter.conversationId = "120363123456789@newsletter";
        Run r = drive(newsletter);
        assertEquals(ParserRegistry.OutcomeKind.IGNORED, r.out.kind);
        assertNotNull("the drop reason is recorded, never silent", r.out.reason);
        assertTrue(r.out.reason.contains("announcement"));
    }
}
