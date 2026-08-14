package com.replymate.core.listener;

import com.replymate.core.model.Channel;
import com.replymate.core.ports.KvStore;

/** P-intelligence-13: group conversations are OFF BY DEFAULT but never globally
 *  blocked — a clear user setting controls whether ReplyMate listens to (and
 *  drafts replies for) group chats, globally with a per-app override.
 *
 *  Resolution (first match wins):
 *    1. per-channel override "groups.enabled.<channel.wire>" = "1"/"0";
 *    2. the global master "groups.enabled" = "1" (any other value = off).
 *  Everything is fail-closed: a missing/corrupt value reads as OFF, exactly
 *  like a fresh install. Pure + Android-free so every rule is JVM-pinned. */
public final class GroupPolicy {

    /** Master switch. Absent or "0" = groups are dropped at ingest (default). */
    public static final String KV_GLOBAL = "groups.enabled";

    private GroupPolicy() { }

    /** Per-app override key. "", "1" or "0"; "" = inherit the master. */
    public static String keyFor(Channel ch) {
        return "groups.enabled." + (ch == null ? "unknown" : ch.wire);
    }

    public static boolean globalEnabled(KvStore kv) {
        return kv != null && "1".equals(kv.get(KV_GLOBAL, "0"));
    }

    /** Raw override for one channel: "" (inherit), "1" (on), "0" (off). */
    public static String overrideFor(KvStore kv, Channel ch) {
        if (kv == null || ch == null) return "";
        String v = kv.get(keyFor(ch), "");
        return ("1".equals(v) || "0".equals(v)) ? v : "";
    }

    /** The ONE decision every group-shaped event must pass. */
    public static boolean allowed(KvStore kv, Channel ch) {
        String override = overrideFor(kv, ch);
        if ("1".equals(override)) return true;
        if ("0".equals(override)) return false;
        return globalEnabled(kv);
    }

    /** Cycle order for the settings row: inherit → on → off → inherit.
     *  Any unknown value counts as inherit (fail-closed, same as overrideFor). */
    public static String nextOverride(String current) {
        if ("1".equals(current)) return "0";
        if ("0".equals(current)) return "";
        return "1";
    }

    /** Human label for an override value ("inherit (master off)"). */
    public static String describe(KvStore kv, Channel ch) {
        String o = overrideFor(kv, ch);
        if ("1".equals(o)) return "on";
        if ("0".equals(o)) return "off";
        return "inherit (master " + (globalEnabled(kv) ? "on" : "off") + ")";
    }
}
