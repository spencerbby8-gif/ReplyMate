package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Catalog of messaging apps ReplyMate CAN parse and their parser wiring (P3).
 *  This is the ONE place where package→(channel, parser, tier) lives, incl.
 *  alternate package ids (X has both com.x.android and the legacy twitter id).
 *  Providers are added incrementally: a new app = one add() line here + tests.
 *  The tier is declared at registration next to the parser it describes, so UI
 *  labels always reflect the ACTUAL wired parser, never a parallel static map. */
public final class WatchedApps {

    /** Honest capability tier of the wired parser (not of the app itself). */
    public enum Tier {
        /** MessagingStyle: per-sender history, groups, media markers, owner. */
        FULL,
        /** Title+text heuristics: usually right, occasionally approximate. */
        PARTIAL,
        /** Accuracy depends heavily on what the app publishes (category-gated). */
        LIMITED
    }

    /** One supported app: label for UI, channel for storage, parser for extraction,
     *  tier describing that parser's real data quality. */
    public static final class AppDef {
        public final String packageName;
        public final String label;
        public final Channel channel;
        public final NotifParser parser;
        public final Tier tier;

        AppDef(String pkg, String label, Channel channel, NotifParser parser, Tier tier) {
            this.packageName = pkg;
            this.label = label;
            this.channel = channel;
            this.parser = parser;
            this.tier = tier;
        }
    }

    private WatchedApps() { }

    public static List<AppDef> all() {
        List<AppDef> out = new ArrayList<AppDef>();
        add(out, "com.whatsapp", "WhatsApp", Channel.WHATSAPP,
            new MessagingStyleParser(Channel.WHATSAPP), Tier.FULL);
        add(out, "org.telegram.messenger", "Telegram", Channel.TELEGRAM,
            new MessagingStyleParser(Channel.TELEGRAM), Tier.FULL);
        add(out, "org.thoughtcrime.securesms", "Signal", Channel.SIGNAL,
            new MessagingStyleParser(Channel.SIGNAL), Tier.FULL);
        add(out, "com.google.android.apps.messaging", "Google Messages", Channel.GOOGLE_MESSAGES,
            new MessagingStyleParser(Channel.GOOGLE_MESSAGES), Tier.FULL);
        add(out, "com.facebook.orca", "Messenger", Channel.MESSENGER,
            new MessagingStyleParser(Channel.MESSENGER), Tier.FULL);   /* Meta posts MessagingStyle on modern versions; on older plain-text versions this parser degrades to title/text safely */
        add(out, "com.Slack", "Slack", Channel.SLACK,
            new TitleTextParser(Channel.SLACK, false), Tier.PARTIAL);
        add(out, "com.discord", "Discord", Channel.DISCORD,
            new TitleTextParser(Channel.DISCORD, false), Tier.PARTIAL);
        add(out, "com.instagram.android", "Instagram", Channel.INSTAGRAM,
            new TitleTextParser(Channel.INSTAGRAM, true), Tier.LIMITED);
        add(out, "com.x.android", "X", Channel.X,
            new TitleTextParser(Channel.X, true), Tier.LIMITED);
        add(out, "com.twitter.android", "X", Channel.X,
            new TitleTextParser(Channel.X, true), Tier.LIMITED);
        add(out, "com.zhiliaoapp.musically", "TikTok", Channel.TIKTOK,
            new TitleTextParser(Channel.TIKTOK, true), Tier.LIMITED);
        return Collections.unmodifiableList(out);
    }

    private static void add(List<AppDef> out, String pkg, String label, Channel ch,
                            NotifParser p, Tier tier) {
        out.add(new AppDef(pkg, label, ch, p, tier));
    }

    /** First registered def for a channel. */
    public static AppDef defFor(Channel ch) {
        if (ch == null) return null;
        for (AppDef def : all()) if (def.channel == ch) return def;
        return null;
    }

    /** First registered package for a channel (deep-link fallback / installed check). */
    public static String primaryPackageFor(Channel ch) {
        AppDef def = defFor(ch);
        return def == null ? null : def.packageName;
    }

    /** Human label for a channel ("Google Messages"), or the wire id as fallback. */
    public static String labelFor(Channel ch) {
        AppDef def = defFor(ch);
        return def == null ? (ch == null ? "?" : ch.wire) : def.label;
    }

    /** Parser capability tier for a channel, null when not in the catalog (manual). */
    public static Tier tierFor(Channel ch) {
        AppDef def = defFor(ch);
        return def == null ? null : def.tier;
    }

    /** All packages registered for a channel (X has two). */
    public static List<String> packagesFor(Channel ch) {
        List<String> out = new ArrayList<String>();
        if (ch == null) return out;
        for (AppDef def : all()) if (def.channel == ch) out.add(def.packageName);
        return out;
    }
}
