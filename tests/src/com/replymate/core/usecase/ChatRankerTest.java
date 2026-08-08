package com.replymate.core.usecase;

import com.replymate.core.model.Contact;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;

/** P-background-9: the Chats screen ranks by REAL recent meaningful activity —
 *  the latest active conversation stays on top; settings edits don't count. */
public class ChatRankerTest {

    private static Contact c(long id, String name, long updatedAt) {
        Contact c = new Contact();
        c.id = id;
        c.displayName = name;
        c.updatedAt = updatedAt;
        return c;
    }

    private static Map<Long, Long> acts(Object... pairs) {
        Map<Long, Long> m = new HashMap<Long, Long>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put((Long) pairs[i], (Long) pairs[i + 1]);
        }
        return m;
    }

    @Test public void latestActiveConversationStaysOnTop() {
        Contact ada = c(1, "Ada", 100);
        Contact bode = c(2, "Bode", 900_000);                     // edited later…
        Contact chidi = c(3, "Chidi", 50);
        List<Contact> in = new ArrayList<Contact>();
        in.add(ada); in.add(bode); in.add(chidi);
        List<Contact> ranked = ChatRanker.rank(in,
            acts(1L, 5_000L, 3L, 60_000L));                       // Bode has NO messages
        assertEquals("Chidi", ranked.get(0).displayName);         // newest message wins
        assertEquals("Ada", ranked.get(1).displayName);
        assertEquals("Bode", ranked.get(2).displayName);          // edit ≠ activity → bottom
    }

    @Test public void freshOutgoingSendAlsoRaisesTheChat() {
        // a send (OUTGOING ts) is activity too — stored messages have no direction here,
        // the caller passes the latest stored sent_at regardless of direction
        Contact a = c(1, "Ada", 100);
        Contact b = c(2, "Bode", 100);
        List<Contact> in = new ArrayList<Contact>();
        in.add(a); in.add(b);
        List<Contact> ranked = ChatRanker.rank(in, acts(1L, 10L, 2L, 20L));
        assertEquals("Bode", ranked.get(0).displayName);
    }

    @Test public void contactsWithoutMessagesFallBelowAllActiveOnes() {
        Contact withMsgs = c(1, "Ada", 100);
        Contact brandNew = c(2, "New", 999_999);
        List<Contact> in = new ArrayList<Contact>();
        in.add(brandNew); in.add(withMsgs);
        List<Contact> ranked = ChatRanker.rank(in, acts(1L, 1L));
        assertEquals("Ada", ranked.get(0).displayName);
        assertEquals("New", ranked.get(1).displayName);
    }

    @Test public void inputListIsNotMutatedAndNullsAreSafe() {
        Contact a = c(1, "Ada", 1);
        List<Contact> in = new ArrayList<Contact>();
        in.add(a); in.add(null);
        List<Contact> ranked = ChatRanker.rank(in, null);
        assertEquals(1, ranked.size());
        assertEquals(2, in.size());                  // untouched input
        assertEquals(a, ranked.get(0));
    }
}
