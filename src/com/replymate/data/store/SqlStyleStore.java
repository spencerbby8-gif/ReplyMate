package com.replymate.data.store;

import com.replymate.core.model.Scope;
import com.replymate.core.model.StyleProfile;
import com.replymate.core.ports.StyleStore;
import com.replymate.data.dao.StyleDao;

public final class SqlStyleStore implements StyleStore {
    private final StyleDao dao;

    public SqlStyleStore(StyleDao dao) { this.dao = dao; }

    @Override public StyleProfile get(Scope scope, Long contactId) {
        return dao.get(scope, contactId);
    }
    @Override public long upsert(StyleProfile p) { return dao.upsert(p); }
    @Override public void deleteByContact(long contactId) { dao.deleteByContact(contactId); }
}
