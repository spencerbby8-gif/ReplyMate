package com.replymate.core.assistant;

import org.junit.Test;
import static org.junit.Assert.*;

/** P-intelligence-17: the two pure capture/adoption rules behind
 *  AssistantTargetStore — an actionless re-post must never wipe a good target
 *  for the same conversation, and the generate-time live re-probe may adopt a
 *  live notification ONLY when it is the same identified conversation with a
 *  real free-form action (the cross-notification borrow is structurally dead). */
public final class TargetRulesTest {

    @Test public void actionlessRepostOfTheSameConversationKeepsTheStoredTarget() {
        assertFalse(TargetRules.shouldReplaceOnCapture(true, false, true));
    }

    @Test public void freshGeometryWithARealActionAlwaysReplaces() {
        assertTrue(TargetRules.shouldReplaceOnCapture(true, true, true));
        assertTrue(TargetRules.shouldReplaceOnCapture(true, true, false));
        assertTrue(TargetRules.shouldReplaceOnCapture(false, true, true));
    }

    @Test public void aDifferentConversationAlwaysReplaces() {
        assertTrue(TargetRules.shouldReplaceOnCapture(true, false, false));
    }

    @Test public void nothingWorthKeepingReplaces() {
        assertTrue(TargetRules.shouldReplaceOnCapture(false, false, true));
        assertTrue(TargetRules.shouldReplaceOnCapture(false, false, false));
    }

    @Test public void liveAdoptionNeedsAllThreeFacts() {
        assertTrue(TargetRules.mayAdoptLive(true, true, true));
        assertFalse("unidentified stored target — nothing to prove sameness against",
            TargetRules.mayAdoptLive(false, true, true));
        assertFalse("a DIFFERENT live conversation — the borrow this phase kills",
            TargetRules.mayAdoptLive(true, false, true));
        assertFalse("the live notification has no real free-form action anyway",
            TargetRules.mayAdoptLive(true, true, false));
    }
}
