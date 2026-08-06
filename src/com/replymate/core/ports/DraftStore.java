package com.replymate.core.ports;

import com.replymate.core.model.Draft;
import com.replymate.core.model.DraftStatus;
import java.util.List;

/** Draft history + audit trail. Contact-scoped reads only. */
public interface DraftStore {
    long insert(Draft d);
    List<Draft> byContact(long contactId, int limit);   // newest-first
    List<Draft> byVariantGroup(String variantGroup);
    void updateStatus(long draftId, DraftStatus status);
    void updateText(long draftId, String newText);      // user edit → status handled by caller
    void deleteByContact(long contactId);
}
