package com.replymate.core.usecase;

import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.ContactChannel;
import com.replymate.core.ports.ContactStore;
import com.replymate.core.util.Clock;
import com.replymate.core.util.Result;

/** Contact orchestration. Enforces the model invariant:
 *  privateMode ⇒ aiEnabled == false (BLUEPRINT §4). */
public final class ContactService {

    private final ContactStore store;
    private final Clock clock;

    public ContactService(ContactStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public Result<Contact> createManualContact(String displayName, String relationshipType,
                                               String notes, String tone, String language) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) {
            return Result.err("Give this contact a name first.");
        }
        long now = clock.now();
        Contact c = new Contact();
        c.displayName = name;
        c.relationshipType = safe(relationshipType);
        c.relationshipNotes = safe(notes);
        c.toneOverride = safe(tone);
        c.languagePref = safe(language);
        c.createdAt = now;
        c.updatedAt = now;
        c.id = store.insert(c);

        ContactChannel ch = new ContactChannel();
        ch.contactId = c.id;
        ch.channel = Channel.MANUAL;
        ch.remoteKey = "manual:" + c.id;   // unique per contact (UNIQUE(channel, remote_key))
        ch.lastSeenAt = now;
        store.upsertChannel(ch);
        return Result.ok(c);
    }

    public void update(Contact c) {
        if (c == null) return;
        enforceInvariants(c);
        c.updatedAt = clock.now();
        store.update(c);
    }

    /** Listener path (P2): resolve a channel identity to a contact, auto-creating both
     *  the contact and the channel row on first sight, touching lastSeen on later ones.
     *  Serialized: on listener (re)bind the system replays ALL active conversations in a
     *  burst and the background pool could otherwise run two get-or-creates for the same
     *  remoteKey concurrently → duplicate contacts for one chat (P2 repair pass). */
    private static final Object ENSURE_LOCK = new Object();

    public Contact ensureChannelContact(Channel channel, String remoteKey, String displayName) {
        synchronized (ENSURE_LOCK) {
            return ensureLocked(channel, remoteKey, displayName);
        }
    }

    /** P-audit-deep: identity-candidate matching. The primary (most trusted) key is
     *  tried first, then each alias in order; the FIRST existing channel row wins,
     *  so a chat previously keyed by display title keeps its contact when the app
     *  later publishes a native conversation id — the new key is then LINKED as an
     *  additional channel row for the same contact (no fork, no lost history). */
    public Contact ensureChannelContact(Channel channel, String remoteKey, String displayName,
                                        java.util.List<String> aliasKeys) {
        synchronized (ENSURE_LOCK) {
            if (aliasKeys == null || aliasKeys.size() <= 1) {
                return ensureLocked(channel, remoteKey, displayName);
            }
            Contact found = null;
            String foundKey = null;
            for (String key : aliasKeys) {
                ContactChannel row = store.findChannel(channel, key);
                if (row != null) {
                    store.touchChannel(row.id, clock.now());
                    Contact c = store.get(row.contactId);
                    if (c != null) { found = c; foundKey = key; break; }
                }
            }
            if (found == null) {
                return ensureLocked(channel, remoteKey, displayName);
            }
            if (!remoteKey.equals(foundKey) && store.findChannel(channel, remoteKey) == null) {
                // Link the stronger key to the SAME contact for future direct hits.
                ContactChannel alias = new ContactChannel();
                alias.contactId = found.id;
                alias.channel = channel;
                alias.remoteKey = remoteKey;
                alias.lastSeenAt = clock.now();
                store.upsertChannel(alias);
            }
            return found;
        }
    }

    private Contact ensureLocked(Channel channel, String remoteKey, String displayName) {
        ContactChannel existing = store.findChannel(channel, remoteKey);
        long now = clock.now();
        if (existing != null) {
            store.touchChannel(existing.id, now);
            Contact c = store.get(existing.contactId);
            if (c != null) return c;
        }
        Contact c = new Contact();
        String name = displayName == null ? "" : displayName.trim();
        c.displayName = name.isEmpty() ? "Unknown (" + channel.wire + ")" : name;
        c.createdAt = now;
        c.updatedAt = now;
        c.id = store.insert(c);

        ContactChannel ch = new ContactChannel();
        ch.contactId = c.id;
        ch.channel = channel;
        ch.remoteKey = remoteKey;
        ch.lastSeenAt = now;
        store.upsertChannel(ch);
        return c;
    }

    private void enforceInvariants(Contact c) {
        if (c.privateMode) {
            c.aiEnabled = false;
        }
    }

    private static String safe(String s) {
        return s == null ? "" : s.trim();
    }
}
