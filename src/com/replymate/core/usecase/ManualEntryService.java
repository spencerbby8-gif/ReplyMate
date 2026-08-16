package com.replymate.core.usecase;

import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.ContactChannel;
import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import com.replymate.core.model.Source;
import com.replymate.core.ports.ContactStore;
import com.replymate.core.ports.MessageStore;
import com.replymate.core.util.Clock;

/** P-intelligence-18 §3: "+ Them" — the user manually records a MISSED INCOMING
 *  message (the chat app was open, no notification was ever posted, so nothing
 *  on the platform can recover it). Honesty contract — every clause test-pinned:
 *
 *   - stored as an INCOMING message in THE conversation the user is looking at,
 *     attributed to the real participant (their name for 1:1, the named member
 *     for groups — a group entry without a sender name is refused);
 *   - provenance {@link Source#MANUALLY_ADDED} — never notification-captured;
 *   - NO fabricated platform metadata: notifKey stays null, item_class stays "",
 *     no sender key, no timestamps other than "added now";
 *   - the conversation's IDENTITY (convId/convTitle) is COPIED from the contact's
 *     own previously captured rows — never invented; unknown stays unknown;
 *   - it never passes through ingest: no ping, no burst job, no provider call.
 *     It IS picked up by burst/topic/context/memory/history like any incoming
 *     row, so the NEXT draft answers it (and group mentions/verdicts work on it);
 *   - if the missed message later DOES arrive as a (late) notification, the
 *     ingest near-dup collapse (same contact+channel+direction+exact body within
 *     the window) folds it instead of double-processing.
 */
public final class ManualEntryService {

    public enum Outcome { STORED, EMPTY_TEXT, NO_CONTACT, NEEDS_SENDER }

    public static final class Result {
        public final Outcome outcome;
        public final long messageId;     // 0 when nothing was stored
        public final String reason;      // human reason for non-STORED outcomes
        Result(Outcome o, long id, String r) {
            outcome = o; messageId = id; reason = r == null ? "" : r;
        }
    }

    private ManualEntryService() { }

    /**
     * @param saidBy  who actually sent it. Blank falls back to the contact's
     *                display name for 1:1; REQUIRED for group conversations
     *                (the whole point is correct attribution).
     */
    public static Result addIncomingFromThem(ContactStore contacts, MessageStore messages,
                                             Clock clock, long contactId,
                                             String text, String saidBy) {
        Contact contact = contacts == null ? null : contacts.get(contactId);
        if (contact == null) {
            return new Result(Outcome.NO_CONTACT, 0, "that conversation no longer exists");
        }
        String body = text == null ? "" : text.trim();
        if (body.isEmpty()) {
            return new Result(Outcome.EMPTY_TEXT, 0, "type their message first");
        }
        String sender = saidBy == null ? "" : saidBy.trim();
        if (contact.isGroup && sender.isEmpty()) {
            return new Result(Outcome.NEEDS_SENDER, 0,
                "say which member of " + contact.displayName + " sent it"
                    + " — attribution is the point");
        }
        if (sender.isEmpty()) sender = contact.displayName;

        Message m = new Message();
        m.contactId = contactId;
        m.channel = originChannel(contacts, contactId);
        m.direction = Direction.INCOMING;
        m.body = body;
        m.sentAt = clock.now();
        m.source = Source.MANUALLY_ADDED;
        m.notifKey = null;                 // explicit: NOT notification-captured
        m.senderName = sender;
        m.senderKey = "";                  // no platform id exists — never invented
        m.itemClass = "";                  // no notification metadata to classify
        m.contentKind = com.replymate.core.model.ContentKind.TEXT.wire;
        String[] ident = latestConversationIdentity(messages, contactId);
        m.convId = ident[0];
        m.convTitle = ident[1];
        long id = messages.insert(m);
        return new Result(Outcome.STORED, id, "");
    }

    /** The conversation identity from this contact's newest stamped row
     *  ({convId, convTitle}) — {"" ,""} when no row carries identity. Copying
     *  known facts only; nothing is fabricated. */
    public static String[] latestConversationIdentity(MessageStore messages, long contactId) {
        String convId = "", convTitle = "";
        if (messages == null) return new String[]{convId, convTitle};
        java.util.List<Message> recent = messages.lastMessages(contactId, 20);
        for (int i = recent.size() - 1; i >= 0; i--) {   // newest-first walk
            Message m = recent.get(i);
            if (m == null) continue;
            if (convTitle.isEmpty() && m.convTitle != null && !m.convTitle.trim().isEmpty()) {
                convTitle = m.convTitle.trim();
            }
            if (convId.isEmpty() && m.convId != null && !m.convId.trim().isEmpty()) {
                convId = m.convId.trim();
            }
            if (!convId.isEmpty() && !convTitle.isEmpty()) break;
        }
        return new String[]{convId, convTitle};
    }

    /** The app this conversation came from (first non-manual channel), or MANUAL
     *  for a manual-only contact. Channels keep CONTEXT honest (app label, dedupe);
     *  Source keeps PROVENANCE honest (manually added, never captured). */
    private static Channel originChannel(ContactStore contacts, long contactId) {
        for (ContactChannel ch : contacts.channelsByContact(contactId)) {
            if (ch.channel != null && ch.channel != Channel.MANUAL) return ch.channel;
        }
        return Channel.MANUAL;
    }
}
