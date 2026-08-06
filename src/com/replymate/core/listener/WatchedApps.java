package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Catalog of supported messaging apps and their parser wiring (P3-A).
 *  addToRegistry() is the single place where package→(channel, parser) lives,
 *  incl. alternate package ids (X has both com.x.android and legacy com.twitter.android). */
public final class WatchedApps {

    /** One supported app: label for UI, channel for storage, parser for extraction. */
    public static final class AppDef {
        public final String packageName;
        public final String label;
        public final Channel channel;
        public final NotifParser parser;

        AppDef(String pkg, String label, Channel channel, NotifParser parser) {
            this.packageName = pkg;
            this.label = label;
            this.channel = channel;
            this.parser = parser;
        }
    }

    private WatchedApps() { }

    public static List<AppDef> all() {
        List<AppDef> out = new ArrayList<AppDef>();
        add(out, "com.whatsapp", "WhatsApp", Channel.WHATSAPP, new MessagingStyleParser(Channel.WHATSAPP));
        add(out, "org.telegram.messenger", "Telegram", Channel.TELEGRAM, new MessagingStyleParser(Channel.TELEGRAM));
        add(out, "org.thoughtcrime.securesms", "Signal", Channel.SIGNAL, new MessagingStyleParser(Channel.SIGNAL));
        add(out, "com.google.android.apps.messaging", "Google Messages", Channel.GOOGLE_MESSAGES, new MessagingStyleParser(Channel.GOOGLE_MESSAGES));
        add(out, "com.facebook.orca", "Messenger", Channel.MESSENGER, new MessagingStyleParser(Channel.MESSENGER));
        add(out, "com.Slack", "Slack", Channel.SLACK, new TitleTextParser(Channel.SLACK, false));
        add(out, "com.discord", "Discord", Channel.DISCORD, new TitleTextParser(Channel.DISCORD, false));
        add(out, "com.instagram.android", "Instagram", Channel.INSTAGRAM, new TitleTextParser(Channel.INSTAGRAM, true));
        add(out, "com.x.android", "X", Channel.X, new TitleTextParser(Channel.X, true));
        add(out, "com.twitter.android", "X", Channel.X, new TitleTextParser(Channel.X, true));
        add(out, "com.zhiliaoapp.musically", "TikTok", Channel.TIKTOK, new TitleTextParser(Channel.TIKTOK, true));
        return Collections.unmodifiableList(out);
    }

    private static void add(List<AppDef> out, String pkg, String label, Channel ch, NotifParser p) {
        out.add(new AppDef(pkg, label, ch, p));
    }

    /** First registered package for a channel (deep-link fallback / installed check). */
    public static String primaryPackageFor(Channel ch) {
        if (ch == null) return null;
        for (AppDef def : all()) if (def.channel == ch) return def.packageName;
        return null;
    }

    /** Human label for a channel ("Google Messages"), or the wire id as fallback. */
    public static String labelFor(Channel ch) {
        if (ch == null) return "?";
        for (AppDef def : all()) if (def.channel == ch) return def.label;
        return ch.wire;
    }

    /** All packages registered for a channel (X has two). */
    public static List<String> packagesFor(Channel ch) {
        List<String> out = new ArrayList<String>();
        if (ch == null) return out;
        for (AppDef def : all()) if (def.channel == ch) out.add(def.packageName);
        return out;
    }
}
