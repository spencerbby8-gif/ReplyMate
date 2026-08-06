package com.replymate.core.prompt;

import com.replymate.core.ai.Turn;

/** Builds the L4 task turn (BLUEPRINT §5.3). Tone transforms (P1d follow-up) attach
 *  different task texts through this same entry point. */
public final class TaskComposer {

    private TaskComposer() { }

    public static Turn defaultTask(String ownerName, String partnerName) {
        return Turn.user("Read the conversation above and write " + ownerName + "'s next reply to "
            + partnerName + ". Output only the reply text.");
    }
}
