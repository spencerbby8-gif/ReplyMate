package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import com.replymate.core.usecase.ContactService;
import com.replymate.core.usecase.ProfileService;
import com.replymate.fakes.Fakes;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-listener-foundation §10: shape-contract fixtures — SANITIZED structural
 *  fixtures derived from the official MessagingStyle documentation
 *  (developer.android.com Notification.MessagingStyle: getMessages/getHistoric
 *  Messages/getConversationTitle/isGroupConversation, RemoteInput free-form
 *  actions) and the stable notification shapes the watched apps are documented
 *  to publish. Content strings are placeholders, not real device bodies — the
 *  raw-keyed real shapes are captured on-device by the ListenerTrace §3 probe
 *  and folded back here as they arrive. Synthetic unit suites elsewhere stay
 *  untouched; this file is deliberately separate so the provenance is obvious.
 *
 *  Every case runs through the REGISTERED parser (WatchedApps catalog) plus the
 *  real IngestCoordinator — the same boundary chain a device notification
 *  travels: extract→parse→classify→filter→store→ping aggregation. */
public final class DeviceShapeContractTest {

    private Fakes.ContactStoreFake contacts;
    private Fakes.MessageStoreFake messages;
    private Fakes.KvStoreFake kv;
    private IngestCoordinator engine;

    @Before public void setUp() {
        contacts = new Fakes.ContactStoreFake();
        messages = new Fakes.MessageStoreFake();
        kv = new Fakes.KvStoreFake();
        kv.put(ProfileService.KEY_NAME, "Spencer");
        engine = new IngestCoordinator(
            new ContactService(contacts, Fakes.FIXED_CLOCK),
            messages, kv, Fakes.FIXED_CLOCK, Fakes.NOOP_LOG);
    }

    // ---------- sanitized shape builders ----------

    private static RawNotif.Entry entry(String sender, String key, String text, long ts) {
        RawNotif.Entry e = new RawNotif.Entry();
        e.senderName = sender;
        e.senderKey = key;
        e.text = text;
        e.timestampMs = ts;
        return e;
    }

    private static RawNotif.ActionRef replyAction(int source) {
        RawNotif.ActionRef a = new RawNotif.ActionRef();
        a.title = "Reply";
        a.source = source;
        a.remoteFreeForm = true;
        a.resultKey = "key_reply";
        return a;
    }

    /** WhatsApp 1:1 chat post with MessagingStyle + a standard Reply action. */
    private static RawNotif whatsapp11(String text, long ts) {
        RawNotif raw = new RawNotif();
        raw.packageName = "com.whatsapp";
        raw.category = "msg";
        raw.title = "Ada";
        raw.text = text;
        raw.group = Boolean.FALSE;
        raw.conversationId = "2348012345678@s.whatsapp.net";
        raw.sbnKey = "0|com.whatsapp|98421|null|10455";
        raw.postTimeMs = ts;
        raw.messages.add(entry("Ada", "wa-ada", text, ts));
        raw.actions.add(replyAction(RawNotif.ActionRef.SRC_STANDARD));
        return raw;
    }

    /** WhatsApp group post with conversationTitle + per-sender history + the
     *  Reply action living ONLY on the wearable surface (the Android Auto/Wear
     *  channel — the standard action opens the app UI instead). */
    private static RawNotif whatsappGroup(long ts) {
        RawNotif raw = new RawNotif();
        raw.packageName = "com.whatsapp";
        raw.category = "msg";
        raw.title = "Weekend crew";
        raw.convTitle = "Weekend crew";
        raw.text = "latest line";
        raw.group = Boolean.TRUE;
        raw.conversationId = "2348012345678-1555@g.us";
        raw.sbnKey = "0|com.whatsapp|777|null|10455";
        raw.postTimeMs = ts;
        raw.messages.add(entry("Ada", "wa-ada", "plan stands", ts - 1000));
        raw.messages.add(entry("Bim", "wa-bim", "latest line", ts));
        raw.actions.add(replyAction(RawNotif.ActionRef.SRC_WEARABLE));
        return raw;
    }

    /** WhatsApp self-status / housekeeping card (backup progress): the exact
     *  shape owner row 9 must always reject. */
    private static RawNotif whatsappSelfStatus() {
        RawNotif raw = new RawNotif();
        raw.packageName = "com.whatsapp";
        raw.title = "WhatsApp";
        raw.text = "Backing up messages …";
        raw.ongoing = true;
        raw.progressMax = 100;
        raw.sbnKey = "0|com.whatsapp|11|null|10455";
        raw.postTimeMs = 4000;
        return raw;
    }

    /** Discord 1:1 DM — MessagingStyle with exactly one entry; the DM title IS
     *  the other party. */
    private static RawNotif discordDm(String text, long ts) {
        RawNotif raw = new RawNotif();
        raw.packageName = "com.discord";
        raw.category = "msg";
        raw.title = "Ada";
        raw.text = text;
        raw.group = Boolean.FALSE;
        raw.conversationId = "dm-4477445588";
        raw.sbnKey = "0|com.discord|31337|null|10456";
        raw.postTimeMs = ts;
        raw.messages.add(entry("Ada", "discord-user-111", text, ts));
        raw.actions.add(replyAction(RawNotif.ActionRef.SRC_STANDARD));
        return raw;
    }

    /** Discord server-channel single post WITHOUT MessagingStyle — the
     *  announcement-shaped no-history shape P-19/P-20 honesty must keep closed. */
    private static RawNotif discordChannelNoHistory() {
        RawNotif raw = new RawNotif();
        raw.packageName = "com.discord";
        raw.category = "msg";
        raw.title = "#announcements";
        raw.convTitle = "#announcements";
        raw.text = "Server maintenance at 2:00";
        raw.group = Boolean.TRUE;
        raw.conversationId = "5599887766";
        raw.sbnKey = "0|com.discord|90125|null|10456";
        raw.postTimeMs = 9000;
        return raw;
    }

    // ---------- WhatsApp ----------

    @Test public void whatsapp11MessagingStyleParseAndIngest() {
        NotifParser parser = WatchedApps.defFor(Channel.WHATSAPP).parser;
        NotifParser.Result r = parser.parse(whatsapp11("ping at lunch?", 5000));
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);
        assertEquals(1, r.events.size());
        NotifEvent e = r.events.get(0);
        assertEquals("Ada", e.senderName);
        assertTrue(e.hasFreeFormReply);          // direct-reply capability present

        IngestReport rep = engine.handle(r.events, null);
        assertEquals(1, rep.stored);
        assertEquals(1, rep.pings.size());       // 1:1 ⇒ proactive ping
    }

    @Test public void whatsapp11BurstOnePostAggregatesOnePing() {
        NotifParser parser = WatchedApps.defFor(Channel.WHATSAPP).parser;
        RawNotif raw = whatsapp11("third", 3000);
        raw.messages.clear();
        raw.messages.add(entry("Ada", "wa-ada", "first", 1000));
        raw.messages.add(entry("Ada", "wa-ada", "second", 2000));
        raw.messages.add(entry("Ada", "wa-ada", "third", 3000));
        NotifParser.Result r = parser.parse(raw);
        assertEquals(3, r.events.size());

        IngestReport rep = engine.handle(r.events, null);
        assertEquals(3, rep.stored);
        assertEquals(1, rep.pings.size());       // one aggregated ping per contact
    }

    @Test public void whatsappGroupRespectsOptInDefaultOff() {
        NotifParser parser = WatchedApps.defFor(Channel.WHATSAPP).parser;
        NotifParser.Result r = parser.parse(whatsappGroup(6000));
        assertEquals(2, r.events.size());
        assertTrue(r.events.get(0).hasFreeFormReply);   // wearable surface counts

        IngestReport rep = engine.handle(r.events, null);
        // Shipped policy (canonical pin: GroupOptInTest): with groups OFF by
        // default a group item is DROPPED entirely — no rows, no contact, no
        // ping — "enable group chats in Sources to listen".
        assertEquals(0, rep.stored);
        assertEquals(0, rep.pings.size());
        assertTrue(contacts.all().isEmpty());
    }

    @Test public void whatsappGroupOptInPingsOnce() {
        kv.put(GroupPolicy.KV_GLOBAL, "1");
        NotifParser.Result r = WatchedApps.defFor(Channel.WHATSAPP).parser.parse(
            whatsappGroup(6000));
        IngestReport rep = engine.handle(r.events, null);
        assertEquals(2, rep.stored);
        assertEquals(1, rep.pings.size());
    }

    @Test public void whatsappRepostAndHistoryExtensionNeverDuplicates() {
        NotifParser parser = WatchedApps.defFor(Channel.WHATSAPP).parser;

        // v1: one entry, one post.
        IngestReport first = engine.handle(parser.parse(whatsapp11("ping?", 5000)).events, null);
        assertEquals(1, first.stored);

        // exact re-post (SBN key churn identical, same content) → nothing new.
        IngestReport dup = engine.handle(parser.parse(whatsapp11("ping?", 5000)).events, null);
        assertEquals(0, dup.stored);
        assertEquals(1, dup.duplicates);

        // re-post with EXTENDED MessagingStyle history (same key, added entry,
        // plus the Reply action moved surfaces) → only the NEW entry stores.
        RawNotif extended = whatsapp11("ping? again", 5999);
        extended.messages.clear();
        extended.messages.add(entry("Ada", "wa-ada", "ping?", 5000));
        extended.messages.add(entry("Ada", "wa-ada", "ping? again", 5999));
        extended.actions.clear();
        extended.actions.add(replyAction(RawNotif.ActionRef.SRC_WEARABLE));
        IngestReport rep2 = engine.handle(parser.parse(extended).events, null);
        assertEquals("only the genuinely new entry may store", 1, rep2.stored);
    }

    @Test public void whatsappShapeUpgradeRepostDoesNotDuplicate() {
        NotifParser parser = WatchedApps.defFor(Channel.WHATSAPP).parser;

        // v1: NO MessagingStyle — single-shot title/text fallback shape.
        RawNotif plain = whatsapp11("ping?", 5000);
        plain.messages.clear();
        IngestReport a = engine.handle(parser.parse(plain).events, null);
        assertEquals(1, a.stored);

        // v2: same notification re-posted WITH MessagingStyle (same sender,
        // body, timestamp) — history upgrade must not double-store the line.
        RawNotif styled = whatsapp11("ping?", 5000);   // identical content+ts
        IngestReport b = engine.handle(parser.parse(styled).events, null);
        assertEquals(0, b.stored);
    }

    @Test public void whatsappSelfStatusIsRejectedBeforeAnythingElse() {
        NotifParser parser = WatchedApps.defFor(Channel.WHATSAPP).parser;
        NotifParser.Result r = parser.parse(whatsappSelfStatus());
        assertEquals(NotifParser.Result.Kind.IGNORE, r.kind);
        IngestReport rep = engine.handle(r.events, null);
        assertEquals(0, rep.stored);
        assertEquals(0, rep.pings.size());
        assertTrue(contacts.all().isEmpty());    // no contact born from status chrome
    }

    // ---------- Discord ----------

    @Test public void discordDmSingleSenderMessagingStyle() {
        NotifParser parser = WatchedApps.defFor(Channel.DISCORD).parser;
        NotifParser.Result r = parser.parse(discordDm("on my way", 6000));
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);
        assertEquals(1, r.events.size());
        assertEquals("Ada", r.events.get(0).senderName);   // person, never a channel

        IngestReport rep = engine.handle(r.events, null);
        assertEquals(1, rep.stored);
        assertEquals(1, rep.pings.size());
    }

    @Test public void discordNoHistoryAnnouncementShapeStaysClosed() {
        kv.put(GroupPolicy.KV_GLOBAL, "1");     // groups ON — honesty must survive anyway
        NotifParser parser = WatchedApps.defFor(Channel.DISCORD).parser;
        NotifParser.Result r = parser.parse(discordChannelNoHistory());
        assertEquals(NotifParser.Result.Kind.EVENTS, r.kind);

        IngestReport rep = engine.handle(r.events, null);
        assertEquals(1, rep.stored);            // stored as attributed context
        assertEquals(0, rep.pings.size());      // …but announcement ⇒ never pinged/drafted
    }
}
