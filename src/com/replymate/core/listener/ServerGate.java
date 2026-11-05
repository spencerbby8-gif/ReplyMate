package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import com.replymate.core.ports.KvStore;

/** P21-production §3: Discord SERVER CHANNELS are their own category — separate
 *  from 1:1 DMs and from group DMs — with an explicit, OFF-BY-DEFAULT opt-in.
 *
 *  Why a separate gate (not the groups toggle):
 *   - a 1:1 DM is a private conversation (always processed when Discord is on);
 *   - a group DM is a private multi-person conversation (governed by the groups
 *     opt-in — GroupPolicy);
 *   - a SERVER channel is a semi-public room: it can produce announcements,
 *     bot/webhook broadcasts and hundreds of humans. It must never be swept into
 *     "groups" silently, and it must never masquerade as a private message.
 *
 *  Detection metadata (honest about limits): Android exposes no "server" flag.
 *  Discord server notifications are group conversations whose conversation title
 *  carries the "#channel" marker — the same structural tell the parser layer has
 *  used since P3 (looksLikeGroup). Group DMs and 1:1 DMs do NOT carry it. When
 *  Discord changes that shape, events fall back to the groups path (never to
 *  the 1:1 path), which stays default-OFF too — fail-closed either way.
 *
 *  Extra capability rule: inside a server channel an item WITHOUT a free-form
 *  quick-reply action can never be answered, so it is demoted to STORE_ONLY —
 *  announcements, bot and webhook broadcasts carry no reply affordance, so this
 *  single metadata rule keeps them draft-proof without any name blacklist. */
public final class ServerGate {

    private ServerGate() { }

    /** Per-app opt-in key ("", "1" or "0"); absent = OFF (fail-closed). */
    public static String keyFor(Channel ch) {
        return "servers.enabled." + (ch == null ? "unknown" : ch.wire);
    }

    /** True only on an explicit "1". Unlike GroupPolicy there is NO global
     *  master switch — opening server firehoses is a per-app decision. */
    public static boolean allowed(KvStore kv, Channel ch) {
        return kv != null && "1".equals(kv.get(keyFor(ch), "0"));
    }

    /** Structural server-channel tell for one parsed event. Discord-only today —
     *  other apps' channel surfaces are handled by their own gates (Slack's '#'
     *  convention is documented in the support matrix, not changed here).
     *  P21-review tightening: the marker must LEAD ("#general", "#general · HQ").
     *  A group DM merely CONTAINING '#' ("Study #crew") stays a group DM — the
     *  substring match mis-routed those into the (default-OFF) server bucket.
     *  Residual, fail-closed either way: a group DM literally NAMED "#…" is a
     *  server here (dropped unless opted in, never drafted silently; if Discord
     *  drops the marker one day, events degrade to the groups gate, also OFF
     *  by default). */
    public static boolean isServerEvent(NotifEvent e) {
        if (e == null || e.channel != Channel.DISCORD || !e.group) return false;
        String t = e.conversationTitle == null ? "" : e.conversationTitle.trim();
        return t.startsWith("#");
    }
}
