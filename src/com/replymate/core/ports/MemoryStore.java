package com.replymate.core.ports;

import com.replymate.core.model.ContactSummary;
import com.replymate.core.model.MemoryFact;
import java.util.List;

/** Per-contact memory layers M2 (summary) + M3 (facts). ISOLATION: contact-scoped only; this is the
 *  single choke point that also filters private_mode contacts' rows (belt & braces with PromptBuilder). */
public interface MemoryStore {
    // facts
    List<MemoryFact> activeFacts(long contactId);                 // disabled=0, ranked by MemoryEngine later
    List<MemoryFact> allFacts(long contactId);                    // memory browser
    long upsertFact(MemoryFact f);                                // merge on (contact_id, text_norm)
    void setFactPinned(long factId, boolean pinned);
    void setFactDisabled(long factId, boolean disabled);
    void deleteFact(long factId);

    // summaries
    ContactSummary latestSummary(long contactId);                 // null if none
    long insertSummary(ContactSummary s);

    // isolation housekeeping
    void deleteAllForContact(long contactId);
}
