package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import com.replymate.fakes.Fakes;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

/** Routing, watch-gating and per-app stats of the P3 parser registry. */
public class ParserRegistryTest {

    private ParserRegistry registry;
    private Fakes.KvStoreFake kv;
    private ListenerStats stats;

    @Before public void setUp() {
        registry = new ParserRegistry();
        kv = new Fakes.KvStoreFake();
        stats = new ListenerStats(kv);
    }

    private static Set<Channel> everything() {
        Set<Channel> all = new HashSet<Channel>();
        for (Channel c : Channel.values()) if (c != Channel.MANUAL) all.add(c);
        return all;
    }

    @Test public void catalogCoversAllTenWatchedApps() {
        assertEquals(Channel.WHATSAPP, registry.channelForPackage("com.whatsapp"));
        assertEquals(Channel.TELEGRAM, registry.channelForPackage("org.telegram.messenger"));
        assertEquals(Channel.SIGNAL, registry.channelForPackage("org.thoughtcrime.securesms"));
        assertEquals(Channel.GOOGLE_MESSAGES, registry.channelForPackage("com.google.android.apps.messaging"));
        assertEquals(Channel.MESSENGER, registry.channelForPackage("com.facebook.orca"));
        assertEquals(Channel.SLACK, registry.channelForPackage("com.Slack"));
        assertEquals(Channel.DISCORD, registry.channelForPackage("com.discord"));
        assertEquals(Channel.INSTAGRAM, registry.channelForPackage("com.instagram.android"));
        assertEquals(Channel.X, registry.channelForPackage("com.x.android"));
        assertEquals(Channel.TIKTOK, registry.channelForPackage("com.zhiliaoapp.musically"));
        assertNull(registry.channelForPackage("com.spotify.music"));
        assertNull(registry.channelForPackage(null));
    }

    @Test public void legacyTwitterPackageStillMapsToX() {
        assertEquals("X alternate package must stay supported",
            Channel.X, registry.channelForPackage("com.twitter.android"));
        assertEquals(2, WatchedApps.packagesFor(Channel.X).size());
        assertEquals("com.x.android", WatchedApps.primaryPackageFor(Channel.X));
        assertEquals("WhatsApp", WatchedApps.labelFor(Channel.WHATSAPP));
    }

    @Test public void whatsappFullMessagingStyleParses() {
        RawNotif raw = ParserFixtures.styleDm(
            "com.whatsapp", "Amara", ParserFixtures.T_GREET, ParserFixtures.T_FOLLOW);
        ParserRegistry.Outcome out = registry.route(raw.packageName, raw, everything(), stats);
        assertEquals(ParserRegistry.OutcomeKind.PARSED, out.kind);
        assertEquals(Channel.WHATSAPP, out.channel);
        assertEquals(2, out.events.size());
        assertEquals("Amara", out.events.get(0).senderName);
        assertEquals(ParserFixtures.T_FOLLOW, out.events.get(1).text);
        assertEquals(1, stats.receivedOf(Channel.WHATSAPP));
        assertEquals(1, stats.parsedOf(Channel.WHATSAPP));
    }

    @Test public void telegramSignalGmessagesMessengerShareTheFullParser() {
        String[][] cases = {
            {"org.telegram.messenger", "telegram"},
            {"org.thoughtcrime.securesms", "signal"},
            {"com.google.android.apps.messaging", "gmessages"},
            {"com.facebook.orca", "messenger"},
        };
        for (String[] c : cases) {
            RawNotif raw = ParserFixtures.styleDm(c[0], "Ada", "hi", ParserFixtures.T_GREET);
            ParserRegistry.Outcome out = registry.route(c[0], raw, everything(), stats);
            assertEquals(c[0] + " should parse fully",
                ParserRegistry.OutcomeKind.PARSED, out.kind);
            assertEquals(c[1], out.channel.wire);
            assertEquals(2, out.events.size());
        }
    }

    @Test public void unwatchedPackageIsSilentAndUnmetered() {
        RawNotif raw = ParserFixtures.titleText("com.spotify.music", "Spotify", "new mix", null);
        ParserRegistry.Outcome out = registry.route(raw.packageName, raw, everything(), stats);
        assertEquals(ParserRegistry.OutcomeKind.NOT_WATCHED, out.kind);
        assertNull(out.channel);
        for (Channel c : Channel.values()) {
            assertEquals(0, stats.receivedOf(c));
        }
    }

    @Test public void disabledAppIsDroppedBeforeParsing() {
        RawNotif raw = ParserFixtures.styleDm("com.whatsapp", "Amara", "hi", "there");
        Set<Channel> enabled = everything();
        enabled.remove(Channel.WHATSAPP);
        ParserRegistry.Outcome out = registry.route(raw.packageName, raw, enabled, stats);
        assertEquals(ParserRegistry.OutcomeKind.DISABLED, out.kind);
        assertNull("nothing may be produced for a disabled app", out.events);
        assertEquals(1, stats.receivedOf(Channel.WHATSAPP));
        assertEquals("gated before parse", 0, stats.parsedOf(Channel.WHATSAPP));
        assertEquals(1, stats.ignoredOf(Channel.WHATSAPP));
    }

    @Test public void nullNotificationFailsSafelyWithReason() {
        ParserRegistry.Outcome out = registry.route("com.whatsapp", null, everything(), stats);
        assertEquals(ParserRegistry.OutcomeKind.FAILED, out.kind);
        assertNotNull(out.reason);
        assertEquals(1, stats.failedOf(Channel.WHATSAPP));
        assertEquals(stats.lastReasonOf(Channel.WHATSAPP),
            kv.get("listener.stats.whatsapp.last_reason", ""));
        assertFalse(stats.lastReasonOf(Channel.WHATSAPP).isEmpty());
    }

    @Test public void noReadableContentIsIgnoredNotFailed() {
        RawNotif raw = ParserFixtures.raw("com.whatsapp");      // title/text/messages all absent
        ParserRegistry.Outcome out = registry.route(raw.packageName, raw, everything(), stats);
        assertEquals(ParserRegistry.OutcomeKind.IGNORED, out.kind);
        assertEquals(1, stats.ignoredOf(Channel.WHATSAPP));
        assertEquals(0, stats.failedOf(Channel.WHATSAPP));
    }

    @Test public void nonMessageCategoryNoiseIgnoredForLimitedApps() {
        RawNotif promo = ParserFixtures.titleText(
            "com.zhiliaoapp.musically", "TikTok", ParserFixtures.T_PROMO, "promo");
        ParserRegistry.Outcome out = registry.route(promo.packageName, promo, everything(), stats);
        assertEquals(ParserRegistry.OutcomeKind.IGNORED, out.kind);
        assertTrue(out.reason.contains("promo"));
    }

    @Test public void groupTitlesFlowThroughForGroups() {
        RawNotif g = ParserFixtures.styleGroup("com.whatsapp", "Family", "Ada", ParserFixtures.T_GROUP);
        ParserRegistry.Outcome out = registry.route(g.packageName, g, everything(), stats);
        assertEquals(ParserRegistry.OutcomeKind.PARSED, out.kind);
        assertTrue(out.events.get(0).group);
        assertEquals("Family", out.events.get(0).conversationTitle);
        assertEquals("Ada", out.events.get(0).senderName);
    }

    @Test public void enabledFromKvHonorsDefaultsAndExplicitToggles() {
        Set<Channel> defaults = EnumSet.of(Channel.WHATSAPP, Channel.TELEGRAM);

        Set<Channel> fresh = ParserRegistry.enabledFromKv(kv, defaults);
        assertTrue(fresh.contains(Channel.WHATSAPP));
        assertTrue(fresh.contains(Channel.TELEGRAM));
        assertFalse("new apps default OFF", fresh.contains(Channel.SIGNAL));
        assertFalse("manual is never a listener source", fresh.contains(Channel.MANUAL));

        kv.put("watch.signal", "1");
        kv.put("watch.whatsapp", "0");          // explicit off beats default-on
        Set<Channel> tuned = ParserRegistry.enabledFromKv(kv, defaults);
        assertTrue(tuned.contains(Channel.SIGNAL));
        assertFalse(tuned.contains(Channel.WHATSAPP));
        assertTrue(tuned.contains(Channel.TELEGRAM));
    }

    @Test public void tiersMatchTheWiredParsersPerChannel() {
        // The label a user sees comes from the REGISTERED parser, nothing else.
        assertEquals(WatchedApps.Tier.FULL, WatchedApps.tierFor(Channel.WHATSAPP));
        assertEquals(WatchedApps.Tier.FULL, WatchedApps.tierFor(Channel.TELEGRAM));
        assertEquals(WatchedApps.Tier.FULL, WatchedApps.tierFor(Channel.SIGNAL));
        assertEquals(WatchedApps.Tier.FULL, WatchedApps.tierFor(Channel.GOOGLE_MESSAGES));
        assertEquals(WatchedApps.Tier.FULL, WatchedApps.tierFor(Channel.MESSENGER));
        assertEquals(WatchedApps.Tier.PARTIAL, WatchedApps.tierFor(Channel.SLACK));
        assertEquals(WatchedApps.Tier.PARTIAL, WatchedApps.tierFor(Channel.DISCORD));
        assertEquals(WatchedApps.Tier.LIMITED, WatchedApps.tierFor(Channel.INSTAGRAM));
        assertEquals(WatchedApps.Tier.LIMITED, WatchedApps.tierFor(Channel.X));
        assertEquals(WatchedApps.Tier.LIMITED, WatchedApps.tierFor(Channel.TIKTOK));
        assertNull("manual mode is not a watched source", WatchedApps.tierFor(Channel.MANUAL));
        for (WatchedApps.AppDef def : WatchedApps.all()) {
            assertEquals("tier of " + def.packageName + " must equal its parser class",
                def.parser instanceof MessagingStyleParser
                    ? WatchedApps.Tier.FULL
                    : (def.channel == Channel.SLACK || def.channel == Channel.DISCORD
                        ? WatchedApps.Tier.PARTIAL : WatchedApps.Tier.LIMITED),
                def.tier);
        }
    }

    @Test public void statsReasonIsTruncatedAndContentFree() {
        StringBuilder longReason = new StringBuilder();
        for (int i = 0; i < 100; i++) longReason.append('x');
        stats.failed(Channel.DISCORD, longReason.toString());
        assertEquals(60, stats.lastReasonOf(Channel.DISCORD).length());
        stats.received(Channel.DISCORD);
        stats.received(Channel.DISCORD);
        assertEquals(2, stats.receivedOf(Channel.DISCORD));
    }
}
