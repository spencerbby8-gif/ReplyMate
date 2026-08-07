package com.replymate.core.prompt;

import com.replymate.core.ai.Turn;

/** Builds the L4 task turn (BLUEPRINT §5.3). Tone transforms (P1d follow-up) attach
 *  different task texts through this same entry point.
 *  P-context-honesty: the task explicitly quotes the message being answered and names
 *  the chat app, so the model can never drift onto an older turn or invent context. */
public final class TaskComposer {

    private TaskComposer() { }

    public static Turn defaultTask(String ownerName, String partnerName) {
        return defaultTask(ownerName, partnerName, null, null);
    }

    /** @param latestIncomingText the exact message being answered (may be null — the
     *  generic form is used then); @param appLabel e.g. "WhatsApp" (may be null). */
    public static Turn defaultTask(String ownerName, String partnerName,
                                   String latestIncomingText, String appLabel) {
        StringBuilder t = new StringBuilder("Read the conversation above and write ")
            .append(ownerName).append("'s next reply to ").append(partnerName).append('.');
        if (latestIncomingText != null && !latestIncomingText.trim().isEmpty()) {
            String quoted = latestIncomingText.trim();
            if (quoted.length() > 400) quoted = quoted.substring(0, 400) + "…";
            t.append("\nThe message you're replying to — ").append(partnerName)
             .append("'s latest: \"").append(quoted)
             .append("\". Answer THAT message, not an older one.");
        }
        if (appLabel != null && !appLabel.trim().isEmpty()) {
            t.append("\nThis chat is on ").append(appLabel.trim()).append('.');
        }
        t.append("\nOutput only the reply text.");
        return Turn.user(t.toString());
    }
}
