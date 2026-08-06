package com.replymate.core.ports;

import com.replymate.core.model.StyleSignal;
import java.util.List;

/** Learning signal rows (P4). ISOLATION: contact-scoped only. */
public interface LearningStore {
    long insert(StyleSignal s);
    /** Newest-first, capped by the caller (derivation reads a bounded window). */
    List<StyleSignal> byContact(long contactId, int limit);
    /** Learning "reset" control: delete the contact's signal history. */
    void deleteForContact(long contactId);
}
