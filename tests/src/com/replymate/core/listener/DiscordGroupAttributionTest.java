package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.Message;
import com.replymate.core.usecase.ContactService;
import com.replymate.core.usecase.ProfileService;
import com.replymate.fakes.Fakes;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-20 §2: REPRODUCTION — Discord group conversations lose WHO
 *  said what. Discord posts CONVERSATION notifications (MessagingStyle) for
 *  server channels: conversationTitle "#general", a messages array with
 *  per-sender entries, and the framework's EXTRA_TEXT shadow of the newest
 *  line. The registered parser (TitleTextParser) only reads its MessagingStyle
 *  history as a last resort — when EXTRA_TEXT is non-empty it takes the
 *  title/text path instead, attributing EVERYTHING to the channel itself.
 *  Consequences on a real device, provable in-gate: the newest line is stored
 *  under sender "#general" (a channel, not a person), older burst lines are
 *  LOST entirely (never stored), the participant registry learns a channel as
 *  the only member, and every Discord group message is an announcement-shape
 *  repeat pinned behind reply-capability evidence alone.
 *
 *  After the fix (Discord prefers its own MessagingStyle history when present,
 *  everything else byte-identical), the same notification stores each visible
 *  line under its REAL sender, in order — announcement honesty stays exactly
 *  where the evidence puts it. */
public final class DiscordGroupAttributionTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.KvStoreFake kv;
    private IngestCoordinator engine;
    private NotifParser parser;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        kv = new Fakes.KvStoreFake();
        kv.put(GroupPolicy.KV_GLOBAL, "1");
        kv.put(ProfileService.KEY_NAME, "Spencer");
        engine = new IngestCoordinator(
            new ContactService(contacts, Fakes.FIXED_CLOCK),
            messages, kv, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG);
        parser = WatchedApps.defFor(Channel.DISCORD).parser;
    }

    /** The realistic Discord CONVERSATION notification for #general (what the
     *  shade system actually hands a listener for a server channel). */
    private static RawNotif discordConversationNotif() {
        RawNotif raw = new RawNotif();
        raw.packageName = "com.discord";
        raw.category = "msg";
        raw.title = "#general";
        raw.convTitle = "#general";
        raw.group = Boolean.TRUE;
        raw.conversationId = "4477445566";
        raw.postTimeMs = 1500;
        raw.text = "spencer you coming?";          // framework shadow of newest line
        raw.ownerName = "Spencer";
        RawNotif.Entry a = new RawNotif.Entry();
        a.senderName = "Ada";
        a.senderKey = "discord-user-111";
        a.text = "squad update in 5";
        a.timestampMs = 1000;
        raw.messages.add(a);
        RawNotif.Entry b = new RawNotif.Entry();
        b.senderName = "Bim";
        b.senderKey = "discord-user-222";
        b.text = "spencer you coming?";
        b.timestampMs = 1500;
        raw.messages.add(b);
        RawNotif.ActionRef reply = new RawNotif.ActionRef();
        reply.title = "Reply";
        reply.remoteFreeForm = true;
        reply.resultKey = "key_reply";
        raw.actions.add(reply);
        return raw;
    }

    @Test public void parserPrefersMessagingStyleHistoryWhenDiscordPublishesIt() {
        NotifParser.Result r = parser.parse(discordConversationNotif());
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);
        // THE REPRO: today this is ONE event attributed to "#general" — the
        // channel, not the people — and Ada's line is LOST.
        assertEquals("every visible line is stored, in order", 2, r.events.size());
        NotifEvent first = r.events.get(0);
        NotifEvent second = r.events.get(1);
        assertEquals("Ada", first.senderName);
        assertEquals("squad update in 5", first.text);
        assertEquals("discord-user-111", first.senderKey);
        assertEquals("Bim", second.senderName);
        assertEquals("spencer you coming?", second.text);
        assertEquals("discord-user-222", second.senderKey);
        assertTrue(first.group && second.group);
        assertEquals("#general", first.conversationTitle);
        assertEquals("4477445566", first.conversationId);
        assertTrue("reply capability still rides every event", first.hasFreeFormReply);
    }

    @Test public void ingestLearnsMembersNeverTheChannelAndMentionsStillFire() {
        NotifParser.Result r = parser.parse(discordConversationNotif());
        IngestReport rep = engine.handle(r.events, null);

        assertEquals(2, rep.stored);
        assertEquals("the newest line pings once", 1, rep.pings.size());
        Contact c = contacts.all().get(0);
        assertEquals("#general", c.displayName);
        assertTrue(c.isGroup);
        List<Message> thread = messages.lastMessages(c.id, 10);
        assertEquals("Ada", thread.get(0).senderName);
        assertEquals("Bim", thread.get(1).senderName);
        assertEquals("discord-user-222", thread.get(1).senderKey);
        assertEquals("an owner-mention in the group is a replyable mention",
            "mention", thread.get(1).itemClass);
        assertFalse("the channel itself is never stamped onto human lines",
            "announcement".equals(thread.get(0).itemClass));
        // participants learned at evaluation time (the engagement pipeline's own
        // consumer): people, keyed by stable ids — the channel joins nobody.
        com.replymate.core.usecase.ConversationStateService states =
            new com.replymate.core.usecase.ConversationStateService(kv, Fakes.FIXED_CLOCK);
        com.replymate.core.usecase.ConversationStateService.Evaluation eval =
            states.evaluate(c, thread, "Spencer", null);
        assertEquals(com.replymate.core.convo.Engagement.Verdict.REPLY_REQUIRED,
            eval.engagement.verdict);
        assertEquals("MENTIONED", eval.engagement.reason);
        assertEquals("Bim", eval.engagement.target.senderLabel);
        String reg = kv.get("convo.registry." + c.id, "");
        assertTrue(reg.contains("Ada"));
        assertTrue(reg.contains("Bim"));
        assertFalse(reg.contains("#general"));
    }

    @Test public void singleShotTitleTextShapeKeepsItsEvidenceHonesty() {
        // Control: NO MessagingStyle history published — the classic Discord
        // shape. Parser must stay byte-identical to the P-19/19R behavior:
        // sender echoes the channel, capability gates the announcement branch.
        RawNotif raw = new RawNotif();
        raw.packageName = "com.discord";
        raw.title = "#general";
        raw.convTitle = "#general";
        raw.group = Boolean.TRUE;
        raw.text = "match moved to sunday?";
        raw.postTimeMs = 1000;
        NotifParser.Result r = parser.parse(raw);
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);
        assertEquals(1, r.events.size());
        assertEquals("#general", r.events.get(0).senderName);
        ItemClassifier.Result ic = ItemClassifier.classify(r.events.get(0), new String[0]);
        assertEquals(ItemClass.ANNOUNCEMENT, ic.cls);
    }
}
