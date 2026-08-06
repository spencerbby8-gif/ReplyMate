package com.replymate.data.store;

import com.replymate.core.model.ProviderDef;
import com.replymate.core.ports.ProviderStore;
import com.replymate.data.dao.ProviderDao;

public final class SqlProviderStore implements ProviderStore {
    private final ProviderDao dao;

    public SqlProviderStore(ProviderDao dao) { this.dao = dao; }

    @Override public long upsertActive(ProviderDef def) { return dao.upsertActive(def); }
    @Override public ProviderDef active() { return dao.active(); }
}
