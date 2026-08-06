package com.replymate.core.ports;

import com.replymate.core.model.Scope;
import com.replymate.core.model.StyleProfile;

/** Writing-style profiles (global + per-contact). */
public interface StyleStore {
    StyleProfile get(Scope scope, Long contactId);   // null if absent
    long upsert(StyleProfile p);
    void deleteByContact(long contactId);
}
