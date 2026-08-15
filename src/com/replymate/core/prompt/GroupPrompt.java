package com.replymate.core.prompt;

import com.replymate.core.convo.ConversationState;
import com.replymate.core.convo.Engagement;
import com.replymate.core.convo.Participant;
import java.util.ArrayList;
import java.util.List;

/** P-intelligence-16b: the ConversationState → PROMPT lines (groups only, situation-
 *  line channel — 1:1 prompts keep zero new bytes). Each line is fact-backed by the
 *  state object: participants (collision-aliased), topic (+change), and the reply
 *  target when the engagement evidence produced one. No line is ever composed from
 *  a guess. */
public final class GroupPrompt {

    private GroupPrompt() { }

    public static List<String> lines(ConversationState st, Engagement en) {
        List<String> out = new ArrayList<String>();
        if (st == null || !st.isGroup) return out;

        // same-name members — the model must address the exact person
        if (st.participants.hasCollision()) {
            StringBuilder sb = new StringBuilder(
                "This group has members who share a FIRST NAME: ");
            List<String> aliased = new ArrayList<String>();
            for (Participant p : st.participants.all()) {
                String lbl = st.participants.labelFor(p.stableId);
                if (!lbl.equalsIgnoreCase(p.displayName)) {
                    aliased.add("\"" + p.displayName + "\" and \"" + lbl + "\"");
                }
            }
            for (int i = 0; i < aliased.size() && i < 2; i++) {
                if (i > 0) sb.append("; ");
                sb.append(aliased.get(i));
            }
            sb.append(" — they are DIFFERENT people. Use the exact numbered name when"
                + " addressing one of them; never merge what they said.");
            out.add(sb.toString());
        }

        if (!st.topic.isEmpty()) {
            StringBuilder sb = new StringBuilder("What this burst is about: ")
                .append(st.topic).append('.');
            if (!st.subtopic.isEmpty()) {
                sb.append(" Right now it narrowed to: ").append(st.subtopic).append('.');
            }
            if (!st.previousTopic.isEmpty()) {
                sb.append(" The topic just CHANGED (before: ").append(st.previousTopic)
                  .append(") — answer the new one; the earlier topic is likely settled.");
            }
            out.add(sb.toString());
        }

        if (en != null && en.target != null && en.target.hasIdentity()) {
            String tSnippet = en.target.snippet;
            if (tSnippet.length() > 180) tSnippet = tSnippet.substring(0, 180) + "…";
            if (en.target.confidence == com.replymate.core.convo.ReplyTarget.Confidence.HIGH) {
                out.add(en.target.senderLabel + " is talking to YOU directly: \""
                    + tSnippet + "\". Answer THAT message to " + en.target.senderLabel
                    + " — use their name where it feels natural, and answer their point"
                    + " before anything else.");
            } else {
                out.add("The open question being answered is " + en.target.senderLabel
                    + "'s: \"" + tSnippet + "\" — answer that one, not an older line.");
            }
        } else if (en != null && en.verdict == Engagement.Verdict.REPLY_OPTIONAL) {
            out.add("Nobody asked for you by name in this burst — you are only joining"
                + " in. Keep it light and additive (react, add a thought, or a small"
                + " question); do not take over the conversation.");
        }
        return out;
    }
}
