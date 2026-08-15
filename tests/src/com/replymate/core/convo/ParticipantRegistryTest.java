package com.replymate.core.convo;

import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-16b: participant identities are STABLE (native key > uri > name),
 *  names are learned, and two people who share a name can never be confused. */
public final class ParticipantRegistryTest {

    @Test public void stableIdPrefersNativeKeyThenUriThenName() {
        assertEquals("k:p123", ParticipantRegistry.stableIdFor("p123", "tel:123", "Amara"));
        assertEquals("u:tel:123", ParticipantRegistry.stableIdFor(null, "tel:123", "Amara"));
        assertEquals("n:amara", ParticipantRegistry.stableIdFor("  ", null, "Amara"));
        assertEquals("", ParticipantRegistry.stableIdFor(null, "", "  "));
    }

    @Test public void keyedPersonKeepsIdentityAcrossDisplayNameChanges() {
        ParticipantRegistry r = new ParticipantRegistry();
        Participant p1 = r.observe("person-77", null, "234801", 1000L);
        Participant p2 = r.observe("person-77", null, "Amara", 2000L);
        assertSame("the stable key is one identity", p1, p2);
        assertEquals("latest real name wins", "Amara", p2.displayName);
        assertEquals(2, p2.msgCount);
    }

    @Test public void twoPeopleSharingANameGetDeterministicAliases() {
        ParticipantRegistry r = new ParticipantRegistry();
        r.observe("key-a", null, "Chidi", 1000L);
        r.observe("key-b", null, "Chidi", 2000L);
        assertTrue(r.hasCollision());
        assertEquals("Chidi", r.labelFor("k:key-a"));        // first-seen keeps plain name
        assertEquals("Chidi 2", r.labelFor("k:key-b"));      // second is numbered
    }

    @Test public void uniqueNamesKeepTheirPlainLabel() {
        ParticipantRegistry r = new ParticipantRegistry();
        r.observe("key-a", null, "Amara", 1000L);
        assertFalse(r.hasCollision());
        assertEquals("Amara", r.labelFor("k:key-a"));
    }

    @Test public void aliasesAreStableAcrossPersistenceRoundTrips() {
        ParticipantRegistry r = new ParticipantRegistry();
        r.observe("key-a", null, "Chidi", 1000L);
        r.observe("key-b", null, "Chidi", 2000L);
        ParticipantRegistry back = ParticipantRegistry.fromJson(r.toJson());
        assertEquals("Chidi", back.labelFor("k:key-a"));
        assertEquals("Chidi 2", back.labelFor("k:key-b"));
        assertEquals(2, back.size());
    }

    @Test public void senderlessEntriesCreateNoPhantomMember() {
        ParticipantRegistry r = new ParticipantRegistry();
        assertNull(r.observe(null, null, null, 1000L));
        assertEquals(0, r.size());
    }

    @Test public void weakNameIdIsNeverSilentlyMergedIntoAKeyedId() {
        ParticipantRegistry r = new ParticipantRegistry();
        r.observe(null, null, "Amara", 1000L);          // name-only (weak)
        r.observe("person-9", null, "Amara", 2000L);    // same person, now keyed
        assertEquals("no silent merge — the keyed id is its own truth", 2, r.size());
        assertTrue(r.hasCollision());                    // aliases keep them un-confusable
    }
}
