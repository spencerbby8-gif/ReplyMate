package com.replymate.core.convo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** P-intelligence-16b: the platform-agnostic CONVERSATION STATE — the single object
 *  the group pipeline is rebuilt around. It knows: the group (title + capture-time
 *  fact), the owner (profile name — key only when the platform exposed one),
 *  participants (stable ids + collision-safe labels via the registry), recent
 *  speakers, the MEANINGFUL recent burst (incoming since the owner's last reply,
 *  sender-attributed, historic context flagged), the current topic + active
 *  subtopic (+ the replaced topic when it just changed), and the owner's last
 *  own line. Reply relationships ride on the Engagement/ReplyTarget evaluation —
 *  only native metadata when a platform provides it; never invented. */
public final class ConversationState {

    /** One burst line, attributed. */
    public static final class Line {
        public final String senderStableId;
        public final String senderLabel;
        public final String text;
        public final long tsMs;
        public final String notifKey;
        public Line(String senderStableId, String senderLabel, String text,
                    long tsMs, String notifKey) {
            this.senderStableId = senderStableId == null ? "" : senderStableId;
            this.senderLabel = senderLabel == null ? "" : senderLabel;
            this.text = text == null ? "" : text;
            this.tsMs = tsMs;
            this.notifKey = notifKey == null ? "" : notifKey;
        }
    }

    public final long conversationId;
    public final boolean isGroup;
    public final String groupTitle;
    public final String ownerName;

    public final ParticipantRegistry participants;   // learned, persisted (never null)
    public final List<String> recentSpeakers;        // labels, most-recent-speaker last
    public final List<Line> burst;                   // OLDEST-first meaningful burst
    public final int burstOverflow;                  // lines beyond the window

    public final String topic;                       // "" when nothing substantive yet
    public final String subtopic;                    // "" when same as topic
    public final String previousTopic;               // non-empty only on a fresh change
    public final List<String> topicTerms;            // machine terms behind `topic`

    public final String lastOutgoingText;
    public final long lastOutgoingAt;
    public final long evaluatedAtMs;

    public ConversationState(long conversationId, boolean isGroup, String groupTitle,
                             String ownerName, ParticipantRegistry participants,
                             List<String> recentSpeakers, List<Line> burst,
                             int burstOverflow, String topic, String subtopic,
                             String previousTopic, List<String> topicTerms,
                             String lastOutgoingText, long lastOutgoingAt,
                             long evaluatedAtMs) {
        this.conversationId = conversationId;
        this.isGroup = isGroup;
        this.groupTitle = groupTitle == null ? "" : groupTitle;
        this.ownerName = ownerName == null ? "" : ownerName;
        this.participants = participants == null ? new ParticipantRegistry() : participants;
        List<String> rs = new ArrayList<String>();
        if (recentSpeakers != null) rs.addAll(recentSpeakers);
        this.recentSpeakers = Collections.unmodifiableList(rs);
        List<Line> b = new ArrayList<Line>();
        if (burst != null) b.addAll(burst);
        this.burst = Collections.unmodifiableList(b);
        this.burstOverflow = Math.max(0, burstOverflow);
        this.topic = topic == null ? "" : topic;
        this.subtopic = subtopic == null ? "" : subtopic;
        this.previousTopic = previousTopic == null ? "" : previousTopic;
        List<String> tt = new ArrayList<String>();
        if (topicTerms != null) tt.addAll(topicTerms);
        this.topicTerms = Collections.unmodifiableList(tt);
        this.lastOutgoingText = lastOutgoingText == null ? "" : lastOutgoingText;
        this.lastOutgoingAt = lastOutgoingAt;
        this.evaluatedAtMs = evaluatedAtMs;
    }

    /** The newest burst line, or null when the burst is empty. */
    public Line newestLine() {
        return burst.isEmpty() ? null : burst.get(burst.size() - 1);
    }
}
