package com.replymate.core.prompt;

import com.replymate.core.ai.Turn;

/** Builds the L4 task turn (BLUEPRINT §5.3). Tone transforms (P1d follow-up) attach
 *  different task texts through this same entry point.
 *  P-context-honesty: the task explicitly quotes the message being answered and names
 *  the chat app, so the model can never drift onto an older turn or invent context. */
public final class TaskComposer {

    private TaskComposer() { }

    /** P-intelligence-14: INTENTIONAL generation tasks. Same honesty contract as
     *  the reply task (quote the real anchor, say what must NOT happen, end with
     *  \"Output only the reply text.\"). Every text is deterministic.
     *  @param anchorText the message the intention hangs on (FOLLOW_UP: the
     *    owner's last unanswered outgoing; CLARIFY: their ambiguous latest;
     *    CONTINUE: the pausing message). Never null for those kinds in practice —
     *    {@code DraftService} admission-gates them. OPENER passes null. */
    public static Turn intentionalTask(ComposeKind kind, String ownerName,
                                       String partnerName, String anchorText,
                                       String anchorSender, String appLabel) {
        StringBuilder t = new StringBuilder();
        String anchor = anchorText == null ? "" : anchorText.trim();
        if (anchor.length() > 400) anchor = anchor.substring(0, 400) + "…";
        switch (kind) {
            case FOLLOW_UP:
                t.append("Read the conversation above. ").append(ownerName)
                 .append("'s last message — \"").append(anchor)
                 .append("\" — is still UNANSWERED by ").append(partnerName).append(".\n")
                 .append("Write ").append(ownerName)
                 .append("'s short follow-up bumping THAT message: light, natural,"
                    + " no guilt-tripping, no \"why are you ignoring me\", and never a"
                    + " repeat of the same words. It must fit the relationship and"
                    + " voice above.");
                break;
            case CLARIFY:
                t.append(partnerName).append("'s latest message — \"").append(anchor)
                 .append("\" — is ambiguous or hard to answer confidently.\n")
                 .append("Write ").append(ownerName)
                 .append("'s short clarifying question about THAT message: ask exactly"
                    + " what is unclear (one thing), in ").append(ownerName)
                 .append("'s own voice. Do not answer the message itself, do not"
                    + " stack multiple questions.");
                break;
            case CONTINUE:
                t.append("The conversation with ").append(partnerName)
                 .append(" paused at: \"").append(anchor).append("\".\n")
                 .append("Write ").append(ownerName)
                 .append("'s next message CONTINUING that same topic naturally —"
                    + " move it forward (new detail, next step or shared thought),"
                    + " do not re-answer the quoted message and do not pivot to a"
                    + " random new topic.");
                break;
            case OPENER:
                t.append("Write ").append(ownerName).append("'s opening message to ")
                 .append(partnerName)
                 .append(" — a fresh opener with no topic to continue. Make it natural"
                    + " for the relationship, notes and voice above (a check-in,"
                    + " a shared interest, or a plan hook from what you remember"
                    + " about them). Never creepy, never needy.");
                break;
            default:
                t.append("Write ").append(ownerName).append("'s next message to ")
                 .append(partnerName).append('.');
        }
        if (appLabel != null && !appLabel.trim().isEmpty()) {
            t.append("\nThis chat is on ").append(appLabel.trim()).append('.');
        }
        t.append("\nOutput only the reply text.");
        return Turn.user(t.toString());
    }

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

    /** P-background-6: rapid incoming texts are ONE burst, not N separate questions.
     *  The unread tail (consecutive incoming since the owner's last outgoing) is
     *  quoted as a numbered burst; the model summarizes it into its single point and
     *  writes ONE reply to that point. Token discipline: max 6 items, 180 chars each. */
    public static Turn burstTask(String ownerName, String partnerName,
                                 java.util.List<String> burstTexts, String appLabel) {
        return burstTask(ownerName, partnerName, burstTexts, appLabel, null);
    }

    /** P-intelligence-1 (understood path): the burst task PLUS grounded mechanical
     *  annotations from the understanding layer, appended BEFORE the closing
     *  instructions — but ONLY where a signal actually fired; without annotations the
     *  task text stays byte-identical to the legacy form. */
    public static Turn burstTask(String ownerName, String partnerName,
                                 java.util.List<String> burstTexts, String appLabel,
                                 java.util.List<String> annotations) {
        StringBuilder t = new StringBuilder("Read the conversation above and write ")
            .append(ownerName).append("'s next reply to ").append(partnerName).append('.');
        t.append("\n").append(partnerName).append(" fired ")
            .append(burstTexts.size()).append(" messages in quick succession (a burst):");
        int i = 1;
        for (String m : burstTexts) {
            String q = m == null ? "" : m.trim();
            if (q.length() > 180) q = q.substring(0, 180) + "…";
            t.append("\n").append(i++).append(") \"").append(q).append("\"");
        }
        t.append("\nSummarize the burst into its single point and write ONE reply that"
            + " answers that point (the latest message leads). Ignore pure filler"
            + " repeats (a second \"?\", \"you there\", duplicated lines) — answer the"
            + " thought, not the noise. If they correct themselves mid-burst (\"no wait\","
            + " \"I meant\"), the correction wins over the earlier line. If the topic"
            + " shifts mid-burst, answer the newest topic — the earlier one may already"
            + " be settled. Do NOT answer each message separately, and"
            + " do not reply to an older turn.");
        // P-intelligence-1: mechanical annotations land AFTER the generic guidance,
        // so a grounded signal always reads as the freshest, most specific instruction.
        if (annotations != null) {
            for (String line : annotations) {
                if (line != null && !line.trim().isEmpty()) t.append('\n').append(line.trim());
            }
        }
        if (appLabel != null && !appLabel.trim().isEmpty()) {
            t.append("\nThis chat is on ").append(appLabel.trim()).append('.');
        }
        t.append("\nOutput only the reply text.");
        return Turn.user(t.toString());
    }
}
