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
}
