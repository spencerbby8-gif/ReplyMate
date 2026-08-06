package com.replymate.core.ports;

import java.util.Map;

/** Raw style setting rows (P4 customization storage). contactId == null → GLOBAL row.
 *  ISOLATION: contact rows are read/written strictly per contact. */
public interface StyleSettingStore {
    /** All (key, value) rows for one scope (global when contactId == null). Never null. */
    Map<String, String> all(Long contactId);
    void put(Long contactId, String key, String value);
    /** Remove a contact override (falls back to inherit) or a global key.
     *  Bulk contact cleanup needs no method here: style_setting rows carry
     *  ON DELETE CASCADE, so deleting the contact wipes them. */
    void remove(Long contactId, String key);
}
