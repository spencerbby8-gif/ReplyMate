package com.replymate.core.auth;

import com.replymate.core.json.Json;
import com.replymate.core.json.JsonObj;
import com.replymate.core.util.Clock;
import com.replymate.core.util.Result;

/** Supabase Auth over the official REST API (verified against the live project
 *  + 2026 docs; NO SDK — keeps the zero-third-party-lib constraint). Endpoints:
 *    POST /auth/v1/otp                email code send ({{.Token}} template → 6-digit code)
 *    POST /auth/v1/verify             email code verify (type "email")
 *    POST /auth/v1/signup {}          anonymous guest session
 *    GET  /auth/v1/authorize          OAuth (google) w/ PKCE challenge
 *    POST /auth/v1/token?grant_type=pkce|refresh_token
 *    GET  /auth/v1/user               session validation / user fetch
 *    PUT  /auth/v1/user               update user_metadata (avatar_url, full_name)
 *    POST /auth/v1/logout             sign out (scope=global) */
public final class SupabaseAuth {

    private final AuthTransport transport;
    private final Clock clock;

    public SupabaseAuth(AuthTransport transport, Clock clock) {
        this.transport = transport;
        this.clock = clock;
    }

    /* ------------------------------------------------------------ email OTP */

    /** Sends a 6-digit verification code to the email (never a magic link — the
     *  project email template uses {{ .Token }}). 2xx = code dispatched. */
    public Result<String> sendEmailOtp(String email) {
        if (email == null || email.trim().isEmpty() || !email.contains("@")) {
            return Result.err("Enter a valid email address first.");
        }
        AuthTransport.Response r = transport.call("POST", "/auth/v1/otp",
            Json.write(JsonObj.create().put("email", email.trim())), "");
        if (ok(r.code)) return Result.ok("code sent");
        return Result.err(AuthErrors.map(r.code, r.body).userMessage);
    }

    /** Verifies the 6-digit code; success returns a real session. */
    public Result<AuthSession> verifyEmailOtp(String email, String code) {
        String c = code == null ? "" : code.trim();
        if (c.length() < 6) return Result.err("Enter the 6-digit code from the email.");
        AuthTransport.Response r = transport.call("POST", "/auth/v1/verify",
            Json.write(JsonObj.create()
                .put("type", "email")
                .put("token", c)
                .put("email", email == null ? "" : email.trim())
                ), "");
        if (ok(r.code)) {
            AuthSession s = AuthSession.fromTokenResponse(r.body, "email", clock.now());
            if (s != null) return Result.ok(s);
            return Result.err("The server answered without a session — try again.");
        }
        return Result.err(AuthErrors.map(r.code, r.body).userMessage);
    }

    /* ------------------------------------------------------------ guest */

    /** Anonymous (guest) sign-in: a REAL Supabase session with no identity, so
     *  everything downstream (avatar sync, later linking) works the same way. */
    public Result<AuthSession> signInAnonymously() {
        AuthTransport.Response r = transport.call("POST", "/auth/v1/signup", "{}", "");
        if (ok(r.code)) {
            AuthSession s = AuthSession.fromTokenResponse(r.body, "anonymous", clock.now());
            if (s != null) { s.anonymous = true; return Result.ok(s); }
            return Result.err("The server answered without a session — try again.");
        }
        return Result.err(AuthErrors.map(r.code, r.body).userMessage);
    }

    /* ------------------------------------------------------------ Google OAuth (PKCE) */

    /** The browser URL that starts Google sign-in through Supabase (PKCE, mobile).
     *  redirectTo must be allow-listed in the Supabase dashboard
     *  (Authentication → URL Configuration → Redirect URLs). */
    public String oauthAuthorizeUrl(String provider, String redirectTo, String codeChallenge) {
        StringBuilder q = new StringBuilder("/auth/v1/authorize?provider=")
            .append(urlEnc(provider))
            .append("&redirect_to=").append(urlEnc(redirectTo));
        if (codeChallenge != null && !codeChallenge.isEmpty()) {
            q.append("&code_challenge=").append(urlEnc(codeChallenge))
             .append("&code_challenge_method=s256");
        }
        return q.toString();
    }

    /** Exchanges the OAuth auth code (from the deep link) for a session (PKCE). */
    public Result<AuthSession> exchangePkce(String authCode, String codeVerifier) {
        AuthTransport.Response r = transport.call("POST", "/auth/v1/token?grant_type=pkce",
            Json.write(JsonObj.create()
                .put("auth_code", authCode == null ? "" : authCode)
                .put("code_verifier", codeVerifier == null ? "" : codeVerifier)
                ), "");
        if (ok(r.code)) {
            AuthSession s = AuthSession.fromTokenResponse(r.body, "google", clock.now());
            if (s != null) return Result.ok(s);
            return Result.err("The server answered without a session — try Google again.");
        }
        return Result.err(AuthErrors.map(r.code, r.body).userMessage);
    }

    /* ------------------------------------------------------------ session care */

    /** Rotates a session using its refresh token. Refresh responses may rotate the
     *  refresh token itself — callers MUST persist whatever comes back. */
    public Result<AuthSession> refresh(AuthSession current) {
        if (current == null || current.refreshToken.isEmpty()) {
            return Result.err("Session can't be renewed — sign in again.");
        }
        AuthTransport.Response r = transport.call("POST",
            "/auth/v1/token?grant_type=refresh_token",
            Json.write(JsonObj.create().put("refresh_token", current.refreshToken)), "");
        if (ok(r.code)) {
            AuthSession s = AuthSession.fromTokenResponse(r.body, current.provider, clock.now());
            if (s != null) {
                if (s.refreshToken.isEmpty()) s.refreshToken = current.refreshToken;
                return Result.ok(s);
            }
            return Result.err("The server answered without a session — sign in again.");
        }
        return Result.err(AuthErrors.map(r.code, r.body).userMessage);
    }

    /** Validates a stored session against the server (cold start restore check). */
    public Result<String> getUser(AuthSession s) {
        if (s == null || s.accessToken.isEmpty()) return Result.err("no session");
        AuthTransport.Response r = transport.call("GET", "/auth/v1/user", "", s.accessToken);
        if (ok(r.code)) {
            return Result.ok(com.replymate.core.json.Json.parseObj(r.body)
                .str("id", ""));
        }
        return Result.err(AuthErrors.map(r.code, r.body).userMessage);
    }

    /** Stores profile fields in the user's metadata (display name + avatar URL). */
    public Result<String> updateUserMetadata(AuthSession s, String fullName, String avatarUrl) {
        if (s == null || s.accessToken.isEmpty()) return Result.err("Sign in first.");
        JsonObj data = JsonObj.create();
        if (fullName != null && !fullName.trim().isEmpty()) data.put("full_name", fullName.trim());
        if (avatarUrl != null && !avatarUrl.trim().isEmpty()) data.put("avatar_url", avatarUrl.trim());
        AuthTransport.Response r = transport.call("PUT", "/auth/v1/user",
            Json.write(JsonObj.create().put("data", data)), s.accessToken);
        if (ok(r.code)) return Result.ok("saved");
        return Result.err(AuthErrors.map(r.code, r.body).userMessage);
    }

    /** Server-side sign-out (all sessions, scope=global). Local cleanup is the
     *  caller's job and must happen even when this call fails offline. */
    public Result<String> logout(AuthSession s) {
        if (s == null || s.accessToken.isEmpty()) return Result.ok("already signed out");
        AuthTransport.Response r = transport.call("POST",
            "/auth/v1/logout?scope=global", "", s.accessToken);
        if (ok(r.code) || r.code == 401) return Result.ok("signed out");   // 401 = token already dead = out
        return Result.err(AuthErrors.map(r.code, r.body).userMessage);
    }

    /* ------------------------------------------------------------ utils */

    static boolean ok(int code) {
        return code >= 200 && code < 300;
    }

    static String urlEnc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20");
        } catch (java.io.UnsupportedEncodingException e) {
            return s;   // UTF-8 is always present on the JVM/Android
        }
    }
}
