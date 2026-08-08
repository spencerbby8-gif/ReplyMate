package com.replymate.core.usecase;

import com.replymate.core.model.Contact;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/** P-background-9: the Chats screen ranks by REAL meaningful activity — the latest
 *  stored message per contact (any direction: incoming bursts AND the owner's own
 *  sends both mean the conversation is alive), newest first. Contacts with no
 *  messages yet fall below everything active (creation/edit order within), because
 *  a settings edit is not activity and must never jump a live chat to the top.
 *  Pure JVM so the rule stays pinned, not assumed. */
public final class ChatRanker {

    private ChatRanker() {
    }

    /** @param lastActivityAt contact-id → latest message timestamp (epoch ms).
     *  Contacts missing/zero are treated as "no messages yet". The input list is
     *  NOT mutated. */
    public static List<Contact> rank(List<Contact> contacts,
                                     Map<Long, Long> lastActivityAt) {
        List<Contact> active = new ArrayList<Contact>();
        List<Contact> idle = new ArrayList<Contact>();
        if (contacts != null) {
            for (Contact c : contacts) {
                if (c == null) continue;
                Long ts = lastActivityAt == null ? null : lastActivityAt.get(Long.valueOf(c.id));
                if (ts != null && ts.longValue() > 0) active.add(c); else idle.add(c);
            }
        }
        Collections.sort(active, new Comparator<Contact>() {
            @Override public int compare(Contact a, Contact b) {
                Long ta = lastActivityAt.get(Long.valueOf(a.id));
                Long tb = lastActivityAt.get(Long.valueOf(b.id));
                long xa = ta == null ? 0L : ta.longValue();
                long xb = tb == null ? 0L : tb.longValue();
                if (xa != xb) return xa < xb ? 1 : -1;      // newest activity first
                return a.id < b.id ? 1 : (a.id == b.id ? 0 : -1); // stable: newest id first
            }
        });
        Collections.sort(idle, new Comparator<Contact>() {
            @Override public int compare(Contact a, Contact b) {
                if (a.updatedAt != b.updatedAt) return a.updatedAt < b.updatedAt ? 1 : -1;
                return a.id < b.id ? 1 : (a.id == b.id ? 0 : -1);
            }
        });
        List<Contact> out = new ArrayList<Contact>(active.size() + idle.size());
        out.addAll(active);
        out.addAll(idle);
        return out;
    }
}
