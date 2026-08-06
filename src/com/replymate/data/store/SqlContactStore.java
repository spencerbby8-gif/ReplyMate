package com.replymate.data.store;

import com.replymate.core.model.Channel;
import com.replymate.core.model.Contact;
import com.replymate.core.model.ContactChannel;
import com.replymate.core.ports.ContactStore;
import com.replymate.data.dao.ContactDao;
import java.util.List;

public final class SqlContactStore implements ContactStore {
    private final ContactDao dao;

    public SqlContactStore(ContactDao dao) { this.dao = dao; }

    @Override public long insert(Contact c) { return dao.insert(c); }
    @Override public void update(Contact c) { dao.update(c); }
    @Override public Contact get(long contactId) { return dao.get(contactId); }
    @Override public List<Contact> all() { return dao.all(); }
    @Override public void delete(long contactId) { dao.delete(contactId); }
    @Override public List<ContactChannel> channelsByContact(long contactId) {
        return dao.channelsByContact(contactId);
    }
    @Override public ContactChannel findChannel(Channel channel, String remoteKey) {
        return dao.findChannel(channel, remoteKey);
    }
    @Override public long upsertChannel(ContactChannel ch) { return dao.upsertChannel(ch); }
    @Override public void touchChannel(long channelId, long lastSeenAt) {
        dao.touchChannel(channelId, lastSeenAt);
    }
}
