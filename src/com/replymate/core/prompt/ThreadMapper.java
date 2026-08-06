package com.replymate.core.prompt;

import com.replymate.core.ai.Turn;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import java.util.ArrayList;
import java.util.List;

/** Maps the stored per-contact thread into provider turns (BLUEPRINT L3):
 *  incoming → user turns (prefixed "Name: text"), my outgoing → model turns (plain text). */
public final class ThreadMapper {

    private ThreadMapper() { }

    public static List<Turn> map(List<Message> thread, String partnerName) {
        List<Turn> turns = new ArrayList<Turn>();
        if (thread == null) return turns;
        String prefix = (partnerName == null || partnerName.trim().isEmpty() ? "Them" : partnerName) + ": ";
        for (Message m : thread) {
            if (m == null || m.body == null) continue;
            String body = m.body.trim();
            if (body.isEmpty()) continue;
            if (m.direction == Direction.INCOMING) {
                turns.add(Turn.user(prefix + body));
            } else {
                turns.add(Turn.model(body));
            }
        }
        return turns;
    }
}
