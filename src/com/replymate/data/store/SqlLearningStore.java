package com.replymate.data.store;

import com.replymate.core.model.StyleSignal;
import com.replymate.core.ports.LearningStore;
import com.replymate.data.dao.StyleSignalDao;
import java.util.List;

public final class SqlLearningStore implements LearningStore {
    private final StyleSignalDao dao;

    public SqlLearningStore(StyleSignalDao dao) { this.dao = dao; }

    @Override public long insert(StyleSignal s) { return dao.insert(s); }
    @Override public List<StyleSignal> byContact(long contactId, int limit) {
        return dao.byContact(contactId, limit);
    }
    @Override public void deleteForContact(long contactId) { dao.deleteForContact(contactId); }
}
