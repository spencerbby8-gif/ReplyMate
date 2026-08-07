package com.replymate.core.listener;

import com.replymate.core.model.Direction;
import org.junit.Test;
import static org.junit.Assert.*;

public class MessageClassifierTest {

    @Test public void senderMatchingOwnerIsOutgoing() {
        assertEquals(Direction.OUTGOING, MessageClassifier.directionFor("Kelechi", "Kelechi"));
        assertEquals(Direction.OUTGOING, MessageClassifier.directionFor(" kelechi ", "Kelechi"));
    }

    @Test public void differentSenderIsIncoming() {
        assertEquals(Direction.INCOMING, MessageClassifier.directionFor("Amara", "Kelechi"));
    }

    @Test public void unknownOwnerDefaultsToIncoming() {
        assertEquals(Direction.INCOMING, MessageClassifier.directionFor("Amara", null));
        assertEquals(Direction.INCOMING, MessageClassifier.directionFor("Amara", ""));
    }

    @Test public void nullSenderDefaultsToIncoming() {
        assertEquals(Direction.INCOMING, MessageClassifier.directionFor(null, "Kelechi"));
    }

    /* ---------------- P-audit-deep: Person keys beat display names ---------------- */

    @Test public void matchingPersonKeysAreOutgoingEvenWhenNamesDiffer() {
        assertEquals(Direction.OUTGOING,
            MessageClassifier.directionFor("Kelechi (work)", "Kelechi", "jid-owner", "jid-owner"));
    }

    @Test public void differentKeysAreIncomingEvenWhenNamesMatch() {
        // contact literally shares the owner's display name — names would lie
        assertEquals(Direction.INCOMING,
            MessageClassifier.directionFor("Kelechi", "Kelechi", "jid-contact", "jid-owner"));
    }

    @Test public void missingKeysFallBackToNameComparison() {
        assertEquals(Direction.OUTGOING,
            MessageClassifier.directionFor("Kelechi", "Kelechi", null, "jid-owner"));
        assertEquals(Direction.OUTGOING,
            MessageClassifier.directionFor("Kelechi", "Kelechi", "", ""));
        assertEquals(Direction.INCOMING,
            MessageClassifier.directionFor("Amara", "Kelechi", "jid-contact", null));
    }
}
