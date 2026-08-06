package com.replymate.core.listener;

/** Turns raw notification titles into stable per-contact identities (remote keys).
 *  Group conversations are namespaced so they can never collide with a 1:1 contact. */
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
