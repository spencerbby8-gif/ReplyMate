package com.replymate.core.listener;

import java.util.ArrayList;
import java.util.List;

/** Turns raw notification identity hints into stable per-contact identities
 *  (remote keys) — PER APP, with its OWN native identifiers (P-audit-deep):
 *
 *    1. "cid:<native conversation id>"   the source app's published conversation /
 *       shortcut id (WhatsApp thread JID, Slack/Discord channel id, Telegram chat
 *       id…) — HIGH confidence, never synthesized by us;
 *    2. normalized display title (legacy key, unchanged since P2) — MEDIUM
 *       confidence; fallback when the app publishes no native id;
 *    3. "unknown" — LOW confidence.
 *
 *  No WhatsApp-style rule is forced on any app: each app contributes whatever its
 *  notifications happen to publish and the precedence above applies uniformly.
 *  Group conversations stay namespaced so they can never collide with a 1:1 contact.
 *
 *  Backward compat: keys of form (2) are byte-identical to the pre-0.9.0 scheme, so
 *  existing contacts keep their history; a chat that LATER surfaces a native id is
 *  re-linked to the same contact through the alias mechanism in ContactService. */
public final class IdentityResolver {

    private IdentityResolver() { }

    /** Collapse whitespace + trim; never null. */
    public static String normalizeTitle(String raw) {
        if (raw == null) return "";
        return raw.trim().replaceAll("\\s+", " ");
    }

    /** Stable channel-scoped identity key, e.g. "amara okonkwo" or "group:market women". */
    public static String remoteKeyFor(String conversationTitle, boolean group) {
        String norm = normalizeTitle(conversationTitle);
        if (norm.isEmpty()) norm = "unknown";
        String key = norm.toLowerCase(java.util.Locale.US);
        return group ? "group:" + key : key;
    }

    /** Native-id key: the app's OWN conversation identifier, channel-scoped by the
     *  caller (the contact_channel row is per channel). */
    public static String nativeKeyFor(String conversationId) {
        String id = normalizeTitle(conversationId);
        return id.isEmpty() ? null : "cid:" + id;
    }

    /** Candidate keys in trust order: native conversation id first (when the app
     *  published one), then the legacy display-title key (identity fallback AND the
     *  link to contacts created before native ids were read). Used by the identity
     *  resolver for contact matching, first non-empty wins for storage. */
    public static List<String> keyCandidates(String conversationId,
                                             String conversationTitle, boolean group) {
        List<String> out = new ArrayList<String>();
        String nativeKey = nativeKeyFor(conversationId);
        if (nativeKey != null) out.add(group ? "group:" + nativeKey : nativeKey);
        out.add(remoteKeyFor(conversationTitle, group));
        return out;
    }

    /** The primary (most trusted) candidate — stored as the canonical remote key. */
    public static String primaryKey(String conversationId, String conversationTitle,
                                    boolean group) {
        List<String> c = keyCandidates(conversationId, conversationTitle, group);
        return c.get(0);
    }

    /** Confidence label for a resolved key — surfaced in Prompt Audit. */
    public static String confidenceOf(String remoteKey) {
        String k = remoteKey == null ? "" : remoteKey;
        if (k.isEmpty() || "unknown".equals(k) || "group:unknown".equals(k)) return "low";
        if (k.contains(":cid:") || k.startsWith("cid:")) return "high";
        return "medium";
    }

    /** Human display name, casing preserved, fallback for empty. */
    public static String displayNameFor(String conversationTitle) {
        String norm = normalizeTitle(conversationTitle);
        return norm.isEmpty() ? "Unknown contact" : norm;
    }

    public static String firstNonEmpty(String a, String b, String def) {
        if (a != null && !normalizeTitle(a).isEmpty()) return a;
        if (b != null && !normalizeTitle(b).isEmpty()) return b;
        return def;
    }
}
