package com.replymate.core.model;

/** One generated reply candidate. promptSnapshotJson stores exactly what was sent (audit). */
public class Draft {
    public long id;
    public long contactId;
    public Long inReplyToId;
    public String promptSnapshotJson = "";
    public String replyText = "";
    public String model = "";
    public String variantGroup = "";     // drafts from one generation share a group id
    public DraftStatus status = DraftStatus.GENERATED;
    public long latencyMs;
    public int tokensIn;
    public int tokensOut;
    public long createdAt;

    public Draft() { }
}
