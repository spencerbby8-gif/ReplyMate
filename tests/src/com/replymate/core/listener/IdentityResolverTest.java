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
}
