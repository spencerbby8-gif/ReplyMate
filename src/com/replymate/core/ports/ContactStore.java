package com.replymate.core.ports;

import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.ContactChannel;
import java.util.List;

/** Contacts + their per-app channel identities. ISOLATION: every read is either by-id
 *  (for a known contact) or a plain list for UI — never mixed-contact memory queries. */
public interface ContactStore {
    long insert(Contact c);
    void update(Contact c);
    Contact get(long contactId);              // null if missing
    List<Contact> all();                      // UI inbox/list only
    void delete(long contactId);              // cascades channels/messages/memory via FK

    List<ContactChannel> channelsByContact(long contactId);
    ContactChannel findChannel(Channel channel, String remoteKey);
    long upsertChannel(ContactChannel ch);
    void touchChannel(long channelId, long lastSeenAt);
    /** Fork-heal (P-ux-fix): re-point the duplicate's channel rows at the kept
     *  contact; rows that would collide on UNIQUE(channel, remote_key) are dropped. */
    default void reassignContact(long fromContactId, long toContactId) { }
}
