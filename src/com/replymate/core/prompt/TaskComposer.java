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
        return defaultTask(ownerName, partnerName, latestIncomingText, appLabel, null);
    }

    /** P-audit-deep overload: when the answered message came WITH media (captioned
     *  photo etc.), the task says so honestly — the model replies to the caption,
     *  never pretends to have seen the media (kind may be null/TEXT = no disclosure). */
    public static Turn defaultTask(String ownerName, String partnerName,
                                   String latestIncomingText, String appLabel,
                                   com.replymate.core.model.ContentKind latestKind) {
        return defaultTask(ownerName, partnerName, latestIncomingText, appLabel, latestKind, null);
    }

    /** P-memory-audit overload: latestSenderLabel attributes the answered message to
     *  its ACTUAL sender (group chats). null/empty/same-as-partner = 1:1, no change. */
    public static Turn defaultTask(String ownerName, String partnerName,
                                   String latestIncomingText, String appLabel,
                                   com.replymate.core.model.ContentKind latestKind,
                                   String latestSenderLabel) {
        StringBuilder t = new StringBuilder("Read the conversation above and write ")
            .append(ownerName).append("'s next reply to ").append(partnerName).append('.');
        if (latestIncomingText != null && !latestIncomingText.trim().isEmpty()) {
            String quoted = latestIncomingText.trim();
            if (quoted.length() > 400) quoted = quoted.substring(0, 400) + "…";
            String sender = latestSenderLabel == null || latestSenderLabel.trim().isEmpty()
                ? partnerName : latestSenderLabel.trim();
            t.append("\nThe message you're replying to — ").append(sender).append("'s latest");
            if (!sender.equals(partnerName)) {
                t.append(" in ").append(partnerName);   // group: member ≠ conversation
            }
            t.append(": \"").append(quoted)
             .append("\". Answer THAT message, not an older one.");
            if (latestKind != null && latestKind.isMedia()) {
                t.append("\nThat text was sent together with ").append(latestKind.label())
                 .append(" — you cannot see ").append(
                     latestKind == com.replymate.core.model.ContentKind.AUDIO
                         || latestKind == com.replymate.core.model.ContentKind.VOICE
                             ? "or hear it. Reply only to the text." : "it. Reply only to the text.");
            }
            if (latestKind == com.replymate.core.model.ContentKind.CALL) {
                t.append("\n(Their latest item was a call event, not a typed message —"
                    + " answer it as \"sorry I missed your call\"-style if it makes sense,"
                    + " never invent what was said.)");
            }
        }
        if (appLabel != null && !appLabel.trim().isEmpty()) {
            t.append("\nThis chat is on ").append(appLabel.trim()).append('.');
        }
        t.append("\nOutput only the reply text.");
        return Turn.user(t.toString());
    }
}
