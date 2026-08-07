package com.replymate.core.usecase;

import com.replymate.core.memory.MemoryService;
import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.ContactChannel;
import com.replymate.core.ports.ContactStore;
import com.replymate.core.ports.DraftStore;
import com.replymate.core.ports.KvStore;
import com.replymate.core.ports.LearningStore;
import com.replymate.core.ports.MemoryStore;
import com.replymate.core.ports.MessageStore;
import com.replymate.core.ports.StyleSettingStore;
import com.replymate.core.util.Clock;

/** P-ux-fix fork-heal: merges a duplicate contact (created when the same chat was
 *  seen under different keys, e.g. display title first and native conversation id
 *  later, or a manual contact and a WhatsApp chat with the same person) back into
 *  ONE contact. Every store moves its rows onto the kept contact collision-aware;
 *  nothing the owner saved is lost. */
public final class ContactMerger {

    private final ContactStore contacts;
    private final MessageStore messages;
    private final DraftStore drafts;
    private final LearningStore learning;
    private final StyleSettingStore settings;
    private final MemoryStore memory;
    private final KvStore kv;
    private final Clock clock;

    public ContactMerger(ContactStore contacts, MessageStore messages, DraftStore drafts,
                         LearningStore learning, StyleSettingStore settings,
                         MemoryStore memory, KvStore kv, Clock clock) {
        this.contacts = contacts;
        this.messages = messages;
        this.drafts = drafts;
        this.learning = learning;
        this.settings = settings;
        this.memory = memory;
        this.kv = kv;
        this.clock = clock;
    }

    /** Moves ALL of the duplicate's data onto the kept contact (collision-aware:
     *  on conflicts the kept contact's value wins), clears stale derived caches,
     *  upgrades a placeholder display name, then deletes the duplicate shell. */
    public void mergeInto(long keepId, long dropId) {
        if (keepId == dropId) return;
        Contact keep = contacts.get(keepId);
        Contact drop = contacts.get(dropId);
        if (keep == null || drop == null) return;

        messages.reassignContact(dropId, keepId);
        drafts.reassignContact(dropId, keepId);
        learning.reassignContact(dropId, keepId);
        settings.reassignContact(dropId, keepId);
        memory.reassignContact(dropId, keepId);
        // Learned-style cache is derived from the learning signals — stale after a merge.
        kv.delete(MemoryService.styleKey(keepId));
        kv.delete(MemoryService.styleKey(dropId));
        // Channel rows last (re-pointed, UNIQUE collisions dropped), then the shell goes.
        contacts.reassignContact(dropId, keepId);

        if (isPlaceholderName(keep.displayName) && !isPlaceholderName(drop.displayName)) {
            keep.displayName = drop.displayName;
            keep.updatedAt = clock.now();
            contacts.update(keep);
        }
        contacts.delete(dropId);   // FK cascade wipes anything left behind
    }

    /** Finds fork candidates: other contacts whose normalized display name matches and
     *  which either hold a row on this channel or are manual-only, then merges each
     *  into the keeper. Returns how many duplicates were merged away. */
    public int healNameForks(long keepId, String displayName, Channel channel) {
        String norm = ContactService.normalizeName(displayName);
        if (norm.isEmpty()) return 0;
        int merged = 0;
        for (Contact c : contacts.all()) {
            if (c.id == keepId) continue;
            if (!ContactService.normalizeName(c.displayName).equals(norm)) continue;
            boolean onChannel = false;
            boolean manualOnly = true;
            for (ContactChannel ch : contacts.channelsByContact(c.id)) {
                if (ch.channel == channel) onChannel = true;
                if (ch.channel != Channel.MANUAL) manualOnly = false;
            }
            if (onChannel || manualOnly) {
                mergeInto(keepId, c.id);
                merged++;
            }
        }
        return merged;
    }

    static boolean isPlaceholderName(String name) {
        String n = ContactService.normalizeName(name);
        return n.isEmpty() || n.equals("unknown") || n.startsWith("unknown (");
    }
}
