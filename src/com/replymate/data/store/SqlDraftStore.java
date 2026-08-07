package com.replymate.data.store;

import com.replymate.core.model.Draft;
import com.replymate.core.model.DraftStatus;
import com.replymate.core.ports.DraftStore;
import com.replymate.data.dao.DraftDao;
import java.util.List;

public final class SqlDraftStore implements DraftStore {
    private final DraftDao dao;

    public SqlDraftStore(DraftDao dao) { this.dao = dao; }

    @Override public long insert(Draft d) { return dao.insert(d); }
    @Override public List<Draft> byContact(long contactId, int limit) {
        return dao.byContact(contactId, limit);
    }
    @Override public List<Draft> byVariantGroup(String variantGroup) {
        return dao.byVariantGroup(variantGroup);
    }
    @Override public void updateStatus(long draftId, DraftStatus status) {
        dao.updateStatus(draftId, status);
    }
    @Override public void updateText(long draftId, String newText) { dao.updateText(draftId, newText); }
    @Override public void updateFavorite(long draftId, boolean favorite) {
        dao.updateFavorite(draftId, favorite);
    }
    @Override public void delete(long draftId) { dao.delete(draftId); }
    @Override public void deleteByContact(long contactId) { dao.deleteByContact(contactId); }

    @Override public void reassignContact(long fromContactId, long toContactId) {
        dao.reassignContact(fromContactId, toContactId);
    }
}
