package com.replymate.core.auth;

import com.replymate.core.util.Clock;
import com.replymate.core.util.Result;
import com.replymate.fakes.Fakes;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-auth audit battery (owner's checklist): login (google/email/guest), logout,
 *  restore, avatar metadata, session persistence, error handling, gate routing,
 *  PKCE + deep-link parsing — all against scripted transports, replaying response
 *  shapes captured LIVE from the owner's Supabase project. */
public class AuthFlowTest {

    private static final Clock FIXED = new Clock() {
        @Override public long now() { return 1_754_600_000_000L; }
    };

    /** Scripted transport: returns queued responses, records every call. */
    private static final class FakeTransport implements AuthTransport {
        int nextCode;
        String nextBody = "";
        String lastMethod, lastPath, lastBody, lastBearer;
        java.util.List<String[]> log = new java.util.ArrayList<String[]>();

        FakeTransport respond(int code, String body) {
            this.nextCode = code; this.nextBody = body; return this;
        }
        @Override public Response call(String method, String path, String jsonBody, String bearerJwt) {
            lastMethod = method; lastPath = path; lastBody = jsonBody; lastBearer = bearerJwt;
            log.add(new String[] {method, path, jsonBody, bearerJwt});
            return new Response(nextCode, nextBody);
        }
        String path(int i) { return log.get(i)[1]; }
        String body(int i) { return log.get(i)[2]; }
    }

    private static final String SESSION_JSON =
        "{\"access_token\":\"AT-1\",\"token_type\":\"bearer\",\"expires_in\":3600,"
        + "\"refresh_token\":\"RT-1\",\"user\":{\"id\":\"u-42\",\"email\":\"ada@x.dev\","
        + "\"is_anonymous\":false,\"app_metadata\":{\"provider\":\"email\"},"
        + "\"user_metadata\":{\"full_name\":\"Ada\",\"avatar_url\":\"https://img/x.jpg\"}}}";

    /* ------------------------------------------------------------- email OTP */

    @Test public void emailOtpSendAndVerifyHappyPath() {
        FakeTransport t = new FakeTransport().respond(200, "{}");
        SupabaseAuth auth = new SupabaseAuth(t, FIXED);
        assertTrue(auth.sendEmailOtp("ada@x.dev").ok);
        assertEquals("POST", t.lastMethod);
        assertEquals("/auth/v1/otp", t.lastPath);
        assertTrue(t.lastBody.contains("\"email\":\"ada@x.dev\""));

        t.respond(200, SESSION_JSON);
        Result<AuthSession> r = auth.verifyEmailOtp("ada@x.dev", "123456");
        assertTrue(r.ok);
        assertEquals("/auth/v1/verify", t.lastPath);
        assertTrue(t.lastBody.contains("\"type\":\"email\""));
        assertTrue(t.lastBody.contains("\"token\":\"123456\""));
        AuthSession s = r.value;
        assertEquals("AT-1", s.accessToken);
        assertEquals("RT-1", s.refreshToken);
        assertEquals("u-42", s.userId);
        assertEquals("ada@x.dev", s.email);
        assertEquals("Ada", s.displayName);
        assertEquals("https://img/x.jpg", s.avatarUrl);
        assertFalse(s.anonymous);
        assertTrue(s.expiresAtMs > FIXED.now());
    }

    @Test public void emailOtpValidationAndRateLimitErrorsAreActionable() {
        SupabaseAuth auth = new SupabaseAuth(new FakeTransport(), FIXED);
        assertFalse(auth.sendEmailOtp("not-an-email").ok);
        assertFalse(auth.verifyEmailOtp("ada@x.dev", "12").ok);

        FakeTransport t = new FakeTransport().respond(429,
            "{\"code\":429,\"error_code\":\"over_request_rate_limit\",\"msg\":\"Request rate limit reached\"}");
        SupabaseAuth auth2 = new SupabaseAuth(t, FIXED);
        Result<String> r = auth2.sendEmailOtp("ada@x.dev");
        assertFalse(r.ok);
        assertTrue(r.error, r.error.contains("Too many tries"));

        t.respond(403, "{\"code\":403,\"error_code\":\"invalid_otp\",\"msg\":\"Token has expired or is invalid\"}");
        Result<AuthSession> v = auth2.verifyEmailOtp("ada@x.dev", "999999");
        assertFalse(v.ok);
        assertTrue(v.error, v.error.contains("wrong or expired"));
    }

    /** LIVE-probed error shapes from the owner's actual project (auth-live-probe):
     *  email_address_invalid on send, otp_expired on verify, validation_failed on a
     *  disabled Google provider, flow_state_not_found on a bogus PKCE exchange. */
    @Test public void liveProbedErrorShapesMapToHonestCopy() {
        FakeTransport t = new FakeTransport();
        SupabaseAuth auth = new SupabaseAuth(t, FIXED);

        t.respond(400, "{\"code\":400,\"error_code\":\"email_address_invalid\","
            + "\"msg\":\"Email address \\\"replymate-probe@example.com\\\" is invalid\"}");
        Result<String> send = auth.sendEmailOtp("replymate-probe@example.com");
        assertFalse(send.ok);
        assertTrue(send.error, send.error.contains("can't receive codes"));

        t.respond(403, "{\"code\":403,\"error_code\":\"otp_expired\","
            + "\"msg\":\"Token has expired or is invalid\"}");
        Result<AuthSession> v = auth.verifyEmailOtp("ada@x.dev", "000000");
        assertFalse(v.ok);
        assertTrue(v.error, v.error.contains("wrong or expired"));

        t.respond(400, "{\"code\":400,\"error_code\":\"validation_failed\","
            + "\"msg\":\"Unsupported provider: provider is not enabled\"}");
        Result<AuthSession> google = auth.exchangePkce("x", "y");
        assertFalse(google.ok);
        assertTrue(google.error, google.error.contains("switched off"));

        t.respond(404, "{\"code\":404,\"error_code\":\"flow_state_not_found\","
            + "\"msg\":\"invalid flow state, no valid flow state found\"}");
        Result<AuthSession> pkce = auth.exchangePkce("bogus", "bogus");
        assertFalse(pkce.ok);
        assertTrue(pkce.error, pkce.error.contains("expired"));
    }

    /* ------------------------------------------------------------- guest */

    @Test public void anonymousGuestHappyPathAndLiveDisabledError() {
        FakeTransport t = new FakeTransport().respond(200,
            "{\"access_token\":\"AT-G\",\"token_type\":\"bearer\",\"expires_in\":3600,"
            + "\"refresh_token\":\"RT-G\",\"user\":{\"id\":\"g-1\",\"email\":\"\","
            + "\"is_anonymous\":true}}");
        SupabaseAuth auth = new SupabaseAuth(t, FIXED);
        Result<AuthSession> r = auth.signInAnonymously();
        assertTrue(r.ok);
        assertEquals("/auth/v1/signup", t.lastPath);
        assertTrue(r.value.anonymous);
        assertTrue(r.value.isGuest());

        // live-probed reply from the owner's project (anonymous OFF today):
        t.respond(422, "{\"code\":422,\"error_code\":\"anonymous_provider_disabled\","
            + "\"msg\":\"Anonymous sign-ins are disabled\"}");
        Result<AuthSession> bad = auth.signInAnonymously();
        assertFalse(bad.ok);
        assertTrue(bad.error, bad.error.contains("isn't enabled on the ReplyMate server"));
    }

    /* ------------------------------------------------------------- google PKCE */

    @Test public void googleAuthoriseUrlCarriesPkceAndRedirect() {
        SupabaseAuth auth = new SupabaseAuth(new FakeTransport(), FIXED);
        String url = auth.oauthAuthorizeUrl("google", AuthDeepLink.CALLBACK, "CHALLENGE-123");
        assertTrue(url, url.startsWith("/auth/v1/authorize?provider=google"));
        assertTrue(url, url.contains("redirect_to=replymate%3A%2F%2Fauth%2Fcallback"));
        assertTrue(url, url.contains("code_challenge=CHALLENGE-123"));
        assertTrue(url, url.contains("code_challenge_method=s256"));

        FakeTransport t = new FakeTransport().respond(200, SESSION_JSON);
        SupabaseAuth auth2 = new SupabaseAuth(t, FIXED);
        Result<AuthSession> r = auth2.exchangePkce("code-xyz", "verifier-abc");
        assertTrue(r.ok);
        assertEquals("google", r.value.provider);   // provider hint kept
        assertTrue(t.lastPath.startsWith("/auth/v1/token?grant_type=pkce"));
        assertTrue(t.lastBody.contains("\"auth_code\":\"code-xyz\""));
        assertTrue(t.lastBody.contains("\"code_verifier\":\"verifier-abc\""));

        t.respond(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Invalid PKCE verifier\"}");
        Result<AuthSession> bad = auth2.exchangePkce("code-xyz", "wrong");
        assertFalse(bad.ok);
        assertTrue(bad.error, bad.error.contains("expired") || bad.error.contains("again"));
    }

    /* ------------------------------------------------------------- session care */

    @Test public void refreshRotatesAndSurvivesMissingNewRefreshToken() {
        FakeTransport t = new FakeTransport().respond(200,
            "{\"access_token\":\"AT-2\",\"token_type\":\"bearer\",\"expires_in\":3600,"
            + "\"user\":{\"id\":\"u-42\",\"email\":\"ada@x.dev\",\"is_anonymous\":false,"
            + "\"app_metadata\":{\"provider\":\"email\"}}}");
        SupabaseAuth auth = new SupabaseAuth(t, FIXED);
        AuthSession cur = AuthSession.fromTokenResponse(SESSION_JSON, "email", FIXED.now());
        Result<AuthSession> r = auth.refresh(cur);
        assertTrue(r.ok);
        assertEquals("AT-2", r.value.accessToken);
        assertEquals("RT-1", r.value.refreshToken);   // kept old when server omits rotation

        AuthSession noRt = new AuthSession();
        noRt.accessToken = "x";
        assertFalse(auth.refresh(noRt).ok);

        t.respond(401, "{\"code\":401,\"error_code\":\"bad_jwt\",\"msg\":\"invalid JWT\"}");
        Result<AuthSession> dead = auth.refresh(curReuse(SESSION_JSON));
        assertFalse(dead.ok);
        assertTrue(dead.error, dead.error.contains("expire") || dead.error.contains("sign in"));
    }

    private static AuthSession curReuse(String json) {
        return AuthSession.fromTokenResponse(json, "email", FIXED.now());
    }

    @Test public void logoutTreatsDeadTokenAsOutAndMapsErrors() {
        FakeTransport t = new FakeTransport().respond(204, "");
        SupabaseAuth auth = new SupabaseAuth(t, FIXED);
        AuthSession s = curReuse(SESSION_JSON);
        assertTrue(auth.logout(s).ok);
        assertTrue(t.lastPath.startsWith("/auth/v1/logout"));
        assertEquals("AT-1", t.lastBearer);

        t.respond(401, "{\"code\":401,\"error_code\":\"bad_jwt\",\"msg\":\"invalid JWT\"}");
        assertTrue("a dead server token already means signed out", auth.logout(s).ok);

        t.respond(500, "{}");
        assertFalse(auth.logout(s).ok);
        t.respond(-1, "UnknownHostException");
        Result<String> off = auth.logout(s);
        assertFalse(off.ok);
        assertTrue(off.error, off.error.contains("internet"));
    }

    @Test public void getUserValidatesRestoreAndAvatarMetadataRoundTrips() {
        FakeTransport t = new FakeTransport().respond(200, "{\"id\":\"u-42\"}");
        SupabaseAuth auth = new SupabaseAuth(t, FIXED);
        AuthSession s = curReuse(SESSION_JSON);
        assertTrue(auth.getUser(s).ok);
        assertEquals("GET", t.lastMethod);
        assertEquals("AT-1", t.lastBearer);

        t.respond(401, "{\"code\":401,\"error_code\":\"bad_jwt\",\"msg\":\"invalid JWT\"}");
        assertFalse(auth.getUser(s).ok);

        t.respond(200, "{\"id\":\"u-42\"}");
        assertTrue(auth.updateUserMetadata(s, "Ada Lovelace", "https://img/y.jpg").ok);
        assertEquals("PUT", t.lastMethod);
        assertTrue(t.lastBody.contains("\"avatar_url\":\"https://img/y.jpg\""));
        assertTrue(t.lastBody.contains("\"full_name\":\"Ada Lovelace\""));
        t.respond(401, "{\"code\":401,\"error_code\":\"bad_jwt\",\"msg\":\"x\"}");
        assertFalse(auth.updateUserMetadata(s, "Ada", null).ok);
    }

    /* ------------------------------------------------------------- persistence */

    @Test public void sessionStorePersistsAcrossRestartAndCorruptionIsSafe() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        KvSessionStore store = new KvSessionStore(kv);
        assertNull(store.get());
        AuthSession s = curReuse(SESSION_JSON);
        store.put(s);
        // "restart": fresh store over the same kv
        KvSessionStore after = new KvSessionStore(kv);
        AuthSession back = after.get();
        assertNotNull(back);
        assertEquals("AT-1", back.accessToken);
        assertEquals("Ada", back.displayName);
        assertFalse(back.anonymous);

        kv.put("auth.session.v1", "{corrupt!!");
        assertNull("corrupt session => treated signed out, never crashes", after.get());

        after.put(s);
        after.clear();
        assertNull(after.get());

        assertFalse(after.onboardingDone());
        after.setOnboardingDone(true);
        assertTrue(new KvSessionStore(kv).onboardingDone());
    }

    /* ------------------------------------------------------------- gate */

    @Test public void gateRoutesBySessionStateHonestly() {
        assertEquals(AuthGate.Route.AUTH, AuthGate.decide(null, FIXED.now(), false));
        AuthSession guest = new AuthSession();
        guest.accessToken = "AT"; guest.anonymous = true;
        guest.expiresAtMs = FIXED.now() + 3_600_000L;
        assertEquals(AuthGate.Route.HOME, AuthGate.decide(guest, FIXED.now(), false));

        AuthSession real = curReuse(SESSION_JSON);
        real.expiresAtMs = FIXED.now() + 3_600_000L;
        assertEquals(AuthGate.Route.ONBOARDING, AuthGate.decide(real, FIXED.now(), false));
        assertEquals(AuthGate.Route.HOME, AuthGate.decide(real, FIXED.now(), true));
        real.expiresAtMs = FIXED.now() + 60_000L;   // inside the 5-min refresh window
        assertEquals(AuthGate.Route.REFRESH_THEN_HOME,
            AuthGate.decide(real, FIXED.now(), true));

        AuthSession empty = new AuthSession();
        assertEquals(AuthGate.Route.AUTH, AuthGate.decide(empty, FIXED.now(), true));
    }

    /* ------------------------------------------------------------- pkce + deep link */

    @Test public void pkceMatchesRfc7636VectorAndVerifiersAreWellFormed() {
        // RFC 7636 appendix B known answer
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            Pkce.s256Challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"));
        java.security.SecureRandom rnd = new java.security.SecureRandom();
        String v = Pkce.newVerifier(rnd);
        assertEquals(64, v.length());
        assertTrue(v, v.matches("[A-Za-z0-9\\-._~]+"));
        assertNotEquals(v, Pkce.newVerifier(rnd));
    }

    @Test public void deepLinkParsesCodeStateErrorsAndIgnoresForeign() {
        AuthDeepLink ok = AuthDeepLink.parse(
            "replymate://auth/callback?code=abc-123&state=st-9");
        assertTrue(ok.isCallback());
        assertEquals("abc-123", ok.code);
        assertEquals("st-9", ok.state);
        assertEquals("", ok.error);

        AuthDeepLink denied = AuthDeepLink.parse(
            "replymate://auth/callback?error=access_denied&error_description=User%20closed");
        assertTrue(denied.isCallback());
        assertEquals("access_denied", denied.error);
        assertEquals("User closed", denied.errorDesc);

        AuthDeepLink fragErr = AuthDeepLink.parse(
            "replymate://auth/callback#error=server_error&error_description=upstream%20down");
        assertEquals("server_error", fragErr.error);

        assertFalse(AuthDeepLink.parse("https://evil.example/x?code=steal").isCallback());
        assertFalse(AuthDeepLink.parse(null).isCallback());
        assertFalse(AuthDeepLink.parse("").isCallback());
    }

    /* ------------------------------------------------------------- expiry math */

    @Test public void expiryWindowAndTokenResponseMathAreExact() {
        AuthSession s = curReuse(SESSION_JSON);
        // expires_in 3600 → now+1h; fresh: no refresh
        assertFalse(s.needsRefresh(FIXED.now()));
        assertTrue(s.needsRefresh(FIXED.now() + 3_600_000L - 60_000L));

        AuthSession expAt = AuthSession.fromTokenResponse(
            "{\"access_token\":\"A\",\"expires_at\":1754603600,"
            + "\"user\":{\"id\":\"u\"}}", "email", FIXED.now());
        assertEquals(1_754_603_600_000L, expAt.expiresAtMs);
        assertNull("no access_token = no session",
            AuthSession.fromTokenResponse("{\"broken\":true}", "email", 0));
        assertNull(AuthSession.fromTokenResponse("not json", "email", 0));
    }
}
