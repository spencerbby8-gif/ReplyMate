package com.replymate.core.auth;

import com.replymate.core.json.Json;
import com.replymate.core.json.JsonObj;

/** One authenticated (or anonymous-guest) session against Supabase Auth.
 *  Local-first: this is persisted on-device (kv) — the app never asks Supabase
 *  for a session on every cold start; it refreshes only when close to expiry. */
public final class AuthSession {

    public static final long REFRESH_AHEAD_MS = 5 * 60_000L;   // refresh ≤5min before expiry

    public String userId = "";
    public String email = "";          // empty for guest/anonymous sessions
    public String provider = "";       // "google" | "email" | "anonymous"
    public boolean anonymous;          // guest session (Supabase anonymous sign-in)
    public String accessToken = "";
    public String refreshToken = "";
    public long expiresAtMs;           // absolute epoch ms, from expires_at/expiry math
    public String displayName = "";    // from user_metadata when present
    public String avatarUrl = "";      // remote URL in user_metadata (avatar sync)

    public boolean isGuest() {
        return anonymous;
    }

    /** True when the access token is past expiry — or within the refresh window. */
    public boolean needsRefresh(long nowMs) {
        return expiresAtMs > 0 && nowMs + REFRESH_AHEAD_MS >= expiresAtMs;
    }

    public JsonObj toJson() {
        return JsonObj.create()
            .put("user_id", userId)
            .put("email", email)
            .put("provider", provider)
            .put("anonymous", anonymous)
            .put("access_token", accessToken)
            .put("refresh_token", refreshToken)
            .put("expires_at_ms", expiresAtMs)
            .put("display_name", displayName)
            .put("avatar_url", avatarUrl);
    }

    public static AuthSession fromJson(String json) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JsonObj o = Json.parseObj(json);
            AuthSession s = new AuthSession();
            s.userId = o.str("user_id", "");
            s.email = o.str("email", "");
            s.provider = o.str("provider", "");
            s.anonymous = o.bool("anonymous", false);
            s.accessToken = o.str("access_token", "");
            s.refreshToken = o.str("refresh_token", "");
            s.expiresAtMs = o.lng("expires_at_ms", 0L);
            s.displayName = o.str("display_name", "");
            s.avatarUrl = o.str("avatar_url", "");
            return s.accessToken.isEmpty() ? null : s;
        } catch (RuntimeException e) {
            return null;   // corrupt session = treated as signed out, never crashes
        }
    }

    /** Parses a GoTrue session/token response body ({access_token, refresh_token,
     *  expires_in|expires_at, user{…}}) into a session; provider hints the method. */
    public static AuthSession fromTokenResponse(String body, String providerHint, long nowMs) {
        JsonObj o;
        try {
            o = Json.parseObj(body);
        } catch (RuntimeException e) {
            return null;
        }
        String at = o.str("access_token", "");
        if (at.isEmpty()) return null;
        AuthSession s = new AuthSession();
        s.accessToken = at;
        s.refreshToken = o.str("refresh_token", "");
        // GoTrue returns expires_at (unix seconds); expires_in (seconds) as fallback
        long expAt = o.lng("expires_at", 0L);
        s.expiresAtMs = expAt > 0 ? expAt * 1000L
            : nowMs + Math.max(0L, o.lng("expires_in", 0L)) * 1000L;
        JsonObj user = o.obj("user");
        if (user != null) {
            s.userId = user.str("id", "");
            s.email = user.str("email", "");
            JsonObj meta = user.obj("user_metadata");
            if (meta != null) {
                s.displayName = meta.str("full_name", meta.str("name", ""));
                s.avatarUrl = meta.str("avatar_url", "");
            }
            JsonObj appMeta = user.obj("app_metadata");
            s.provider = !providerHint.isEmpty() ? providerHint
                : (appMeta != null ? appMeta.str("provider", "") : "");
            s.anonymous = user.bool("is_anonymous", false);
            if (s.provider.isEmpty()) s.provider = s.anonymous ? "anonymous" : "email";
        }
        return s;
    }
}
