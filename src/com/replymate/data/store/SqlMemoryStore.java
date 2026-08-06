package com.replymate.data.store;

import com.replymate.core.model.ContactSummary;
import com.replymate.core.model.MemoryFact;
import com.replymate.core.ports.MemoryStore;
import com.replymate.data.dao.MemoryDao;
import java.util.List;

public final class SqlMemoryStore implements MemoryStore {
    private final MemoryDao dao;

    public SqlMemoryStore(MemoryDao dao) { this.dao = dao; }

    @Override public List<MemoryFact> activeFacts(long contactId) {
        return dao.activeFacts(contactId);
    }
    @Override public List<MemoryFact> allFacts(long contactId) {
        return dao.allFacts(contactId);
    }
    @Override public long upsertFact(MemoryFact f) { return dao.upsertFact(f); }
    @Override public void setFactPinned(long factId, boolean pinned) {
        dao.setFactPinned(factId, pinned);
    }
    @Override public void setFactDisabled(long factId, boolean disabled) {
        dao.setFactDisabled(factId, disabled);
    }
    @Override public void deleteFact(long factId) { dao.deleteFact(factId); }

    @Override public ContactSummary latestSummary(long contactId) {
        return dao.latestSummary(contactId);
    }
    @Override public long insertSummary(ContactSummary s) { return dao.insertSummary(s); }

    @Override public void deleteAllForContact(long contactId) {
        dao.deleteFactsForContact(contactId);
        dao.deleteSummariesForContact(contactId);
    }
}
