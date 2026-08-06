package com.replymate.core.ports;

import com.replymate.core.model.Channel;
import com.replymate.core.model.Message;
import java.util.List;

/** Per-contact message log. ISOLATION: all reads are contact-scoped. */
public interface MessageStore {
    long insert(Message m);

    /** Like insert, but MUST silently ignore notif_key conflicts (listener re-posts).
     *  Default keeps P1 implementations source-compatible. */
    default long insertIgnore(Message m) {
        return insert(m);
    }

    Message getByNotifKey(Channel channel, String notifKey);      // dedupe, null if absent
    List<Message> lastMessages(long contactId, int limit);        // oldest-first within the window
    int countByContact(long contactId);
    void deleteByContact(long contactId);
}
