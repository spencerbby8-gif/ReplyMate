package com.replymate.core.listener;

import org.junit.Test;
import static org.junit.Assert.*;

public class IdentityResolverTest {

    @Test public void normalizesWhitespace() {
        assertEquals("Amara Okonkwo", IdentityResolver.normalizeTitle("  Amara   Okonkwo \n"));
        assertEquals("", IdentityResolver.normalizeTitle(null));
        assertEquals("", IdentityResolver.normalizeTitle("   "));
    }

    @Test public void remoteKeysAreStableAndCaseInsensitive() {
        assertEquals(IdentityResolver.remoteKeyFor("Amara Okonkwo", false),
                     IdentityResolver.remoteKeyFor("amara okonkwo", false));
        assertEquals("amara okonkwo", IdentityResolver.remoteKeyFor(" Amara   Okonkwo ", false));
    }

    @Test public void groupsGetTheirOwnNamespace() {
        assertEquals("group:family", IdentityResolver.remoteKeyFor("Family", true));
        assertNotEquals(IdentityResolver.remoteKeyFor("Family", true),
                        IdentityResolver.remoteKeyFor("Family", false));
    }

    @Test public void emptyTitleFallsBack() {
        assertEquals("unknown", IdentityResolver.remoteKeyFor(" ", false));
        assertEquals("Unknown contact", IdentityResolver.displayNameFor(null));
    }

    @Test public void displayNameKeepsCasing() {
        assertEquals("Ada Lovelace", IdentityResolver.displayNameFor(" Ada  Lovelace "));
    }

    @Test public void firstNonEmptyPicksFirstUsable() {
        assertEquals("a", IdentityResolver.firstNonEmpty(" ", "a", "d"));
        assertEquals("b", IdentityResolver.firstNonEmpty(null, "b", "d"));
        assertEquals("d", IdentityResolver.firstNonEmpty("", " ", "d"));
    }

    /* ------------- P-audit-deep: native per-app identifiers + confidence ------------- */

    @Test public void nativeConversationIdWinsOverDisplayTitle() {
        java.util.List<String> c = IdentityResolver.keyCandidates(
            "2348012345678@s.whatsapp.net", "Amara", false);
        assertEquals("cid:2348012345678@s.whatsapp.net", c.get(0));  // WhatsApp's own thread id
        assertEquals("amara", c.get(1));                             // legacy key preserved as alias
        assertEquals(c.get(0), IdentityResolver.primaryKey(
            "2348012345678@s.whatsapp.net", "Amara", false));
    }

    @Test public void noNativeIdFallsBackToTheLegacyTitleKey() {
        assertEquals(1, IdentityResolver.keyCandidates(null, "Amara", false).size());
        assertEquals("amara", IdentityResolver.primaryKey(null, "Amara", false));
        assertEquals("amara", IdentityResolver.primaryKey("  ", "Amara", false));
    }

    @Test public void groupsKeepTheirNamespaceWithNativeIdsToo() {
        String key = IdentityResolver.primaryKey("12036@g.us", "Family", true);
        assertTrue(key, key.startsWith("group:"));
        assertTrue(key.contains("cid:12036@g.us"));
    }

    @Test public void confidenceReflectsTheEvidence() {
        assertEquals("high", IdentityResolver.confidenceOf("cid:23480@s.whatsapp.net"));
        assertEquals("high", IdentityResolver.confidenceOf("group:cid:12036@g.us"));
        assertEquals("medium", IdentityResolver.confidenceOf("amara okonkwo"));
        assertEquals("low", IdentityResolver.confidenceOf("unknown"));
        assertEquals("low", IdentityResolver.confidenceOf(null));
    }

    @Test public void neverForcesWhatsappRulesOnOtherApps() {
        // Discord's native id is a channel/room id — stored verbatim, no JID parsing
        String key = IdentityResolver.primaryKey("1042-room", "#design", false);
        assertEquals("cid:1042-room", key);
        assertEquals("high", IdentityResolver.confidenceOf(key));
    }
}
