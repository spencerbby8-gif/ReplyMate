package com.replymate.core.privacy;

import com.replymate.core.model.ProviderDef;
import com.replymate.core.model.ProviderType;
import com.replymate.fakes.Fakes;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-3 (owner directive 7 & 9): privacy MODE DETECTION from the real
 *  provider/account setup — built-in key ⇒ free by design, local endpoints ⇒ local
 *  privacy, own key ⇒ paid/private ONLY when the owner flagged the plan, anything
 *  else ⇒ free. Never collapse these into "an API key exists". */
public final class ProviderPrivacyTest {

    private static ProviderDef def(ProviderType type, String baseUrl, String keyRef) {
        ProviderDef d = new ProviderDef();
        d.id = 7;
        d.type = type;
        d.label = type.label;
        d.baseUrl = baseUrl;
        d.keyRef = keyRef;
        d.isActive = true;
        return d;
    }

    @Test public void noProviderIsNoneMode() {
        assertEquals(ProviderPrivacy.Mode.NONE,
            ProviderPrivacy.modeFor(null, new Fakes.KvStoreFake()));
        assertTrue(ProviderPrivacy.headline(ProviderPrivacy.Mode.NONE, false)
            .contains("No AI provider"));
        assertTrue(ProviderPrivacy.noticeBody(ProviderPrivacy.Mode.NONE, "", false)
            .contains("add your API key"));
    }

    @Test public void builtInKeyIsFreeModeForever() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        ProviderDef d = def(ProviderType.GEMINI,
            "https://generativelanguage.googleapis.com", ProviderPrivacy.BUILT_IN_KEY_REF);
        assertEquals(ProviderPrivacy.Mode.FREE, ProviderPrivacy.modeFor(d, kv));
        assertTrue(ProviderPrivacy.isBuiltIn(d));
        // even if someone flips a paid flag onto it, built-in must NEVER claim private
        kv.put(ProviderPrivacy.planKey(d.id), "paid");
        assertEquals(ProviderPrivacy.Mode.FREE, ProviderPrivacy.modeFor(d, kv));
        String body = ProviderPrivacy.noticeBody(ProviderPrivacy.Mode.FREE, "Gemini", true);
        assertTrue(body.contains("built-in ReplyMate key"));
        assertTrue("free warning states the training-use risk", body.contains("MAY"));
        assertTrue("the warning tells you how to go private",
            body.contains("add YOUR OWN key"));
    }

    @Test public void ownKeyDefaultsToFreeWithFullWarning() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        ProviderDef d = def(ProviderType.OPENAI, "https://api.openai.com/v1", "provider.openai.key");
        assertEquals(ProviderPrivacy.Mode.FREE, ProviderPrivacy.modeFor(d, kv));
        assertFalse(ProviderPrivacy.isBuiltIn(d));
        String head = ProviderPrivacy.headline(ProviderPrivacy.Mode.FREE, false);
        assertTrue(head.contains("may be used to improve"));
        String body = ProviderPrivacy.noticeBody(ProviderPrivacy.Mode.FREE, "OpenAI", false);
        assertTrue(body.contains("OpenAI"));
        assertFalse("built-in copy must not leak into a BYOK notice",
            body.contains("built-in ReplyMate key"));
    }

    @Test public void flaggedPaidPlanIsPrivateAndSoftensTheWarning() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        ProviderDef d = def(ProviderType.ANTHROPIC, "https://api.anthropic.com",
            "provider.anthropic.key");
        kv.put(ProviderPrivacy.planKey(d.id), "paid");
        assertEquals(ProviderPrivacy.Mode.PRIVATE, ProviderPrivacy.modeFor(d, kv));
        String head = ProviderPrivacy.headline(ProviderPrivacy.Mode.PRIVATE, false);
        assertTrue(head.contains("aren't used to train"));
        String body = ProviderPrivacy.noticeBody(ProviderPrivacy.Mode.PRIVATE,
            "Anthropic", false);
        assertTrue(body.contains("not used to train their models"));
        assertTrue(body.contains("never leaves this phone"));
        assertFalse(body.contains("MAY be used"));
    }

    @Test public void freeFlagStaysFree() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        ProviderDef d = def(ProviderType.MISTRAL, "https://api.mistral.ai/v1",
            "provider.mistral.key");
        kv.put(ProviderPrivacy.planKey(d.id), "free");
        assertEquals(ProviderPrivacy.Mode.FREE, ProviderPrivacy.modeFor(d, kv));
    }

    @Test public void localEndpointsAreLocalPrivate() {
        Fakes.KvStoreFake kv = new Fakes.KvStoreFake();
        assertEquals(ProviderPrivacy.Mode.LOCAL, ProviderPrivacy.modeFor(
            def(ProviderType.OLLAMA, "http://localhost:11434/v1", ""), kv));
        assertEquals(ProviderPrivacy.Mode.LOCAL, ProviderPrivacy.modeFor(
            def(ProviderType.OPENAI_COMPAT, "http://127.0.0.1:8080/v1", "k"), kv));
        assertEquals(ProviderPrivacy.Mode.LOCAL, ProviderPrivacy.modeFor(
            def(ProviderType.OPENAI_COMPAT, "http://192.168.1.20:8000/v1", "k"), kv));
        assertEquals(ProviderPrivacy.Mode.LOCAL, ProviderPrivacy.modeFor(
            def(ProviderType.OPENAI_COMPAT, "http://10.0.0.5/v1", "k"), kv));
        assertEquals(ProviderPrivacy.Mode.LOCAL, ProviderPrivacy.modeFor(
            def(ProviderType.OPENAI_COMPAT, "http://172.20.1.4/v1", "k"), kv));
        assertEquals(ProviderPrivacy.Mode.LOCAL, ProviderPrivacy.modeFor(
            def(ProviderType.OPENAI_COMPAT, "http://ai-server.local/v1", "k"), kv));
        // NOT local: 172.15 is outside the private 172.16/12 block; public domains
        assertEquals(ProviderPrivacy.Mode.FREE, ProviderPrivacy.modeFor(
            def(ProviderType.OPENAI_COMPAT, "http://172.15.0.1/v1", "k"), kv));
        assertEquals(ProviderPrivacy.Mode.FREE, ProviderPrivacy.modeFor(
            def(ProviderType.OPENAI_COMPAT, "https://proxy.example.com/v1", "k"), kv));
        assertTrue(ProviderPrivacy.headline(ProviderPrivacy.Mode.LOCAL, false)
            .contains("never leave"));
    }

    @Test public void badgeNeverOverclaims() {
        assertEquals("free mode", ProviderPrivacy.badge(ProviderPrivacy.Mode.FREE));
        assertEquals("private (local)", ProviderPrivacy.badge(ProviderPrivacy.Mode.LOCAL));
        assertEquals("private (paid plan)",
            ProviderPrivacy.badge(ProviderPrivacy.Mode.PRIVATE));
        assertEquals("no provider", ProviderPrivacy.badge(ProviderPrivacy.Mode.NONE));
    }

    @Test public void hostParsingIsSane() {
        assertEquals("api.openai.com", ProviderPrivacy.hostOf("https://api.openai.com/v1"));
        assertEquals("localhost", ProviderPrivacy.hostOf("http://localhost:11434/v1"));
        assertEquals("", ProviderPrivacy.hostOf(null));
        assertEquals("", ProviderPrivacy.hostOf(""));
    }
}
