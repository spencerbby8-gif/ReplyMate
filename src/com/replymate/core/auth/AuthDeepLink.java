package com.replymate.core.auth;

/** Parses the OAuth callback deep link (replymate://auth/callback?code=…&state=…
 *  or ?error=…). Pure parsing: the activity delivers the raw URI string, this
 *  class answers. */
public final class AuthDeepLink {

    public static final String SCHEME = "replymate";
    public static final String HOST = "auth";
    public static final String CALLBACK = SCHEME + "://" + HOST + "/callback";

    public final String code;        // OAuth auth code ("" when absent/error)
    public final String state;       // echoed CSRF state
    public final String error;       // OAuth error name ("" when none)
    public final String errorDesc;   // human-readable from server

    private AuthDeepLink(String code, String state, String error, String errorDesc) {
        this.code = code;
        this.state = state;
        this.error = error;
        this.errorDesc = errorDesc;
    }

    /** @param uriString the raw intent data string; null/foreign = an empty parse. */
    public static AuthDeepLink parse(String uriString) {
        if (uriString == null || uriString.trim().isEmpty()) return none();
        String u = uriString.trim();
        if (!u.startsWith(SCHEME + "://")) return none();

        // Supabase PKCE returns ?code=… in the query; implicit (not used) put tokens
        // in the fragment — we still surface fragment errors for diagnostics.
        String query = after(u, '?');
        String frag = after(u, '#');
        String code = param(query, "code");
        String state = param(query, "state");
        if (state.isEmpty()) state = param(frag, "state");
        String error = param(query, "error");
        String errorDesc = param(query, "error_description");
        if (error.isEmpty()) error = param(frag, "error");
        if (errorDesc.isEmpty()) errorDesc = param(frag, "error_description");
        return new AuthDeepLink(code, state, error, urlDecode(errorDesc));
    }

    public boolean isCallback() {
        return !code.isEmpty() || !error.isEmpty();
    }

    private static AuthDeepLink none() {
        return new AuthDeepLink("", "", "", "");
    }

    private static String after(String u, char marker) {
        int i = u.indexOf(marker);
        if (i < 0) return "";
        String rest = u.substring(i + 1);
        int end = marker == '?' ? rest.indexOf('#') : -1;
        return end >= 0 ? rest.substring(0, end) : rest;
    }

    private static String param(String query, String key) {
        if (query == null || query.isEmpty()) return "";
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            if (pair.substring(0, eq).equals(key)) return urlDecode(pair.substring(eq + 1));
        }
        return "";
    }

    private static String urlDecode(String s) {
        if (s == null || s.isEmpty()) return "";
        try {
            return java.net.URLDecoder.decode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}
