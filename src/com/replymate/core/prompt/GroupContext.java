package com.replymate.core.prompt;

import com.replymate.core.model.Direction;
import com.replymate.core.model.Message;
import java.util.ArrayList;
import java.util.List;

/** P-intelligence-15: the GROUP CONTEXT line. The platform tells us a
 *  conversation is a group at capture time (MessagingStyle isGroupConversation →
 *  persisted on the contact, schema v7); each member's words are attributed per
 *  message (sender_name, schema v6). This line tells the model what the thread
 *  IS — the group's name, the members speaking, and who the owner is — so a
 *  group draft never reads as a 1:1 and the owner's own lines are never taken
 *  for a member's. STRICTLY fact-backed: 1:1 chats carry no group flag and get
 *  no line at all (byte-identical legacy prompt). */
public final class GroupContext {

    public static final int MAX_NAMES = 6;

    private GroupContext() { }

    /**
     * @param thread     recent thread, any order window (incoming rows only are read)
     * @param group      the conversation title / contact display name
     * @param isGroup    the persisted capture-time fact (contact.isGroup)
     * @param ownerName  the owner's display name from their profile (may be "")
     * @return the group context line, or "" when this is not a known group
     */
    public static String header(List<Message> thread, String group, boolean isGroup,
                                String ownerName) {
        if (!isGroup) return "";
        String title = group == null ? "" : group.trim();
        String owner = ownerName == null ? "" : ownerName.trim();
        String self = owner.isEmpty() ? "you, the owner" : owner;

        List<String> speakers = new ArrayList<String>();
        if (thread != null) {
            for (Message m : thread) {
                if (m == null || m.direction != Direction.INCOMING) continue;
                String s = m.senderName == null ? "" : m.senderName.trim();
                if (s.isEmpty() || containsIgnoreCase(speakers, s)) continue;
                if (!title.isEmpty() && s.equalsIgnoreCase(title)) continue;
                speakers.add(s);
            }
        }

        StringBuilder sb = new StringBuilder("This is a GROUP chat");
        if (!title.isEmpty()) sb.append(" called \"").append(title).append('"');
        sb.append('.');
        if (!speakers.isEmpty()) {
            sb.append(" Members speaking here: ");
            int shown = Math.min(speakers.size(), MAX_NAMES);
            for (int i = 0; i < shown; i++) {
                if (i > 0) sb.append(", ");
                sb.append(speakers.get(i));
            }
            if (speakers.size() > MAX_NAMES) {
                sb.append(" and ").append(speakers.size() - MAX_NAMES).append(" more");
            }
            sb.append(" — each member's message arrives as \"Name: text\".");
        }
        sb.append(" Lines with no name are YOURS (you are ").append(self)
          .append("). Answer as ").append(self)
          .append(" to the whole group — name the person you are addressing when it"
              + " matters, and never speak for another member.");
        return sb.toString();
    }

    private static boolean containsIgnoreCase(List<String> list, String s) {
        for (String x : list) if (x.equalsIgnoreCase(s)) return true;
        return false;
    }
}
