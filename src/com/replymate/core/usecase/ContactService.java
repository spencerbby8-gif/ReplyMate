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
    /** P-ux-fix: optional fork-healer (wired by AppContainer) that merges duplicate
     *  contacts the moment a name-match attach or alias hit proves they are one chat. */
    private ContactMerger merger;

    public ContactService(ContactStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public void setMerger(ContactMerger merger) {
        this.merger = merger;
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
            healQuietly(found.id, found.displayName, channel);
            return found;
        }
    }

    private Contact ensureLocked(Channel channel, String remoteKey, String displayName) {
        ContactChannel existing = store.findChannel(channel, remoteKey);
        long now = clock.now();
        if (existing != null) {
            store.touchChannel(existing.id, now);
            Contact c = store.get(existing.contactId);
            if (c != null) {
                // P-ux-fix: a direct hit can still pass by older forks of the same
                // chat (created before name-matching existed) — merge them lazily.
                healQuietly(c.id, c.displayName, channel);
                return c;
            }
        }
        String name = displayName == null ? "" : displayName.trim();

        // P-ux-fix: name-match attach. If this chat's display name matches an existing
        // contact that already lives on this channel — or a MANUAL contact with no app
        // channel yet — the chat belongs to that contact: link the new key to it
        // instead of forking a duplicate conversation. Then heal any older forks of
        // the same name so repeated notifications always land in ONE chat.
        if (meaningfulName(name)) {
            Contact match = matchByName(name, channel);
            if (match != null) {
                ContactChannel link = new ContactChannel();
                link.contactId = match.id;
                link.channel = channel;
                link.remoteKey = remoteKey;
                link.lastSeenAt = now;
                store.upsertChannel(link);
                healQuietly(match.id, match.displayName, channel);
                return match;
            }
        }

        Contact c = new Contact();
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

    /** Name-based candidate for attach: same normalized display name AND either a
     *  channel row on THIS app (same service ⇒ same person/group title) or a
     *  manual-only contact (owner created it by hand, no app link yet). Prefers a
     *  contact already on this channel; otherwise the oldest manual-only one.
     *  Never matches across different apps (a "The Crew" group on WhatsApp is not
     *  the same chat as "The Crew" on Discord). */
    private Contact matchByName(String displayName, Channel channel) {
        String norm = normalizeName(displayName);
        Contact sameChannel = null;
        Contact manualOnly = null;
        for (Contact c : store.all()) {
            if (!normalizeName(c.displayName).equals(norm)) continue;
            boolean onChannel = false;
            boolean onlyManual = true;
            for (ContactChannel ch : store.channelsByContact(c.id)) {
                if (ch.channel == channel) onChannel = true;
                if (ch.channel != Channel.MANUAL) onlyManual = false;
            }
            if (onChannel && (sameChannel == null || c.id < sameChannel.id)) sameChannel = c;
            if (onlyManual && manualOnly == null) manualOnly = c;
        }
        return sameChannel != null ? sameChannel : manualOnly;
    }

    private void healQuietly(long keepId, String displayName, Channel channel) {
        if (merger == null) return;
        try {
            merger.healNameForks(keepId, displayName, channel);
        } catch (RuntimeException ignored) {
            // The listener path must never crash the app over housekeeping.
        }
    }

    /** Normalized comparison key for display names. */
    public static String normalizeName(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(java.util.Locale.US).replaceAll("\\s+", " ");
    }

    /** A name worth identity-matching on: not empty, not a generated placeholder,
     *  and at least two characters (single-letter "names" are usually initials or
     *  partial titles and must not glue chats together). */
    public static boolean meaningfulName(String s) {
        String n = normalizeName(s);
        return n.length() >= 2 && !n.equals("unknown") && !n.startsWith("unknown (");
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
