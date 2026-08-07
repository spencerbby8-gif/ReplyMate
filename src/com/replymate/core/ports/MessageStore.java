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

    /** Everything OLDER than a message id (rolling-summary boundary, P-memory-audit):
     *  strictly this contact's rows, oldest-first, most recent {@code limit} of them. */
    List<Message> olderThanId(long contactId, long beforeId, int limit);

    /** UI inbox search (P3): newest-first messages whose body contains the query.
     *  STRICTLY for the Home screen's "find a conversation" box — results are rendered
     *  as a pick-list only and are NEVER used to assemble AI prompt context (isolation). */
    List<Message> searchByBody(String query, int limit);

    int countByContact(long contactId);
    void deleteByContact(long contactId);
    /** Fork-heal (P-ux-fix): move ALL rows from a duplicate contact into the kept one. */
    default void reassignContact(long fromContactId, long toContactId) { }
}
