package com.replymate.core.prompt;

import com.replymate.core.ai.Turn;
import com.replymate.core.model.ContentKind;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import java.util.ArrayList;
import java.util.List;

/** Maps the stored per-contact thread into provider turns (BLUEPRINT L3):
 *  incoming → user turns (prefixed "Name: text"), my outgoing → model turns (plain).
 *
 *  P-memory-audit honesty rules:
 *    - per-message SENDER attribution: group-chat rows carry the actual sender
 *      (schema v6 sender_name), so the model never treats another member's words
 *      as the contact's own;
 *    - a media-only / placeholder row is rendered as STRUCTURED context
 *      ("Name [sent a photo — content not readable]"), never as if the model
 *      received real message text; captioned media keeps its real caption. */
public final class ThreadMapper {

    private ThreadMapper() { }

    public static List<Turn> map(List<Message> thread, String partnerName) {
        List<Turn> turns = new ArrayList<Turn>();
        if (thread == null) return turns;
        String partner = partnerName == null || partnerName.trim().isEmpty()
            ? "Them" : partnerName.trim();
        for (Message m : thread) {
            if (m == null || m.body == null) continue;
            String body = m.body.trim();
            if (m.direction == Direction.INCOMING) {
                String sender = m.senderName == null || m.senderName.trim().isEmpty()
                    ? partner : m.senderName.trim();
                if (body.isEmpty()) continue;
                if (!PromptBuilder.usableText(body)) {
                    ContentKind kind = m.effectiveKind();
                    if (kind == null || kind == ContentKind.TEXT) continue;
                    turns.add(Turn.user(sender + " [sent " + kind.label()
                        + " — its content is not readable by you; do not describe or guess it]"));
                    continue;
                }
                turns.add(Turn.user(sender + ": " + body));
            } else {
                if (body.isEmpty()) continue;
                turns.add(Turn.model(body));
            }
        }
        return turns;
    }
}
