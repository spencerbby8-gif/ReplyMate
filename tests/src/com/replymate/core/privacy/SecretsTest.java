package com.replymate.core.privacy;

import org.junit.Test;
import static org.junit.Assert.*;

/** P-background-11: the redactor behind every durable diagnostics surface —
 *  pinned BOTH ways: every supported secret shape dies, while ordinary English,
 *  short tokens and already-masked fixtures survive untouched. */
public final class SecretsTest {

    /* --------------------------------------------------- real secret shapes die */

    @Test public void queryCredentialsDie() {
        assertEquals("https://x/v1/models?key=***",
            Secrets.redact("https://x/v1/models?key=AIzaSyAuditOnly0123456789abcd"));
        assertEquals("?key=***&foo=1",
            Secrets.redact("?key=abc123SECRET&foo=1"));
        assertEquals("access_token=***",
            Secrets.redact("access_token=t0k3n-value-9999"));
    }

    @Test public void headerMaterialDies() {
        assertEquals("Authorization: Bearer ***",
            Secrets.redact("Authorization: Bearer sk-live-0000111122223333"));
        assertEquals("x-api-key: ***",
            Secrets.redact("x-api-key: ant-key-000011112222"));
        assertEquals("x-goog-api-key: ***",
            Secrets.redact("x-goog-api-key: AIzaSyAuditOnly0123456789abcd"));
    }

    @Test public void providerKeyShapesDie() {
        assertEquals("***", Secrets.redact("sk-proj-abcdef0123456789abcdef0123456789"));
        assertEquals("***", Secrets.redact("sk-ant-api03-abcdef0123456789abcdef01"));
        assertEquals("***", Secrets.redact("AIzaSyAuditOnlyFakeKey0123456789"));
        assertEquals("***", Secrets.redact("xai-ABCDEFGHabcdefgh0123456789"));
        assertEquals("***", Secrets.redact("sb_publishable_bmOsVq4BeVTpQLLJl0J7yA_e4zf6_Lm"));
        assertEquals("***", Secrets.redact("sb_secret_0123456789abcdefABCDE"));
        String jwt = Secrets.redact("jwt: eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.payloadchunkhere.sig.");
        assertTrue("jwt: ***".equals(jwt) || "jwt: ***.".equals(jwt));
        assertFalse(jwt.contains("payloadchunkhere"));
    }

    @Test public void userInfoCredentialsDie() {
        assertEquals("http://***:***@localhost:11434/v1",
            Secrets.redact("http://admin:hunter22@localhost:11434/v1"));
        assertEquals("http://host/path stays",
            Secrets.redact("http://host/path stays"));
    }

    @Test public void registeredFragmentsDie() {
        java.util.List<String> frags = new java.util.ArrayList<String>();
        frags.add("owner-typed-key-0123456789");
        assertEquals("key is *** end",
            Secrets.redact("key is owner-typed-key-0123456789 end", frags));
        frags.add("tiny");   // under 6 chars — must be ignored, not eat text
        assertEquals("tiny stays, never redacted",
            Secrets.redact("tiny stays, never redacted", frags));
    }

    /* ------------------------------------------------ ordinary text survives */

    @Test public void ordinaryEnglishAndShortTokensSurvive() {
        assertEquals("the bearer of the letter arrived",
            Secrets.redact("the bearer of the letter arrived"));
        assertEquals("monkey=business is fun",
            Secrets.redact("monkey=business is fun"));
        assertEquals("sk-in checks is slang", Secrets.redact("sk-in checks is slang"));
        assertEquals("ask about eyJ tokens in class",
            Secrets.redact("ask about eyJ tokens in class"));
        assertEquals("ok", Secrets.redact("ok"));
        assertEquals("", Secrets.redact(null));
    }

    @Test public void alreadyMaskedSurvives() {
        assertEquals("sk-audit*********************0000",
            Secrets.redact("sk-audit*********************0000"));
    }

    @Test public void geminiErrorBodyLosesItsKeyButKeepsTheMessage() {
        String body = "{\"error\":{\"code\":400,\"message\":\"API key not valid. "
            + "Please pass a valid API key.\",\"status\":\"INVALID_ARGUMENT\"}} "
            + "called https://generativelanguage.googleapis.com/v1beta/models?key=AIzaSyAuditOnly0123456789abcd";
        String out = Secrets.redact(body);
        assertTrue(out.contains("API key not valid"));
        assertTrue(out.contains("INVALID_ARGUMENT"));
        assertFalse(out.contains("AIzaSyAuditOnly0123456789abcd"));
        assertTrue(out.contains("?key=***"));
    }
}
