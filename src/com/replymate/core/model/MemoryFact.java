package com.replymate.core.model;

/** A durable remembered fact about one contact. textNorm is the merge/dedupe key. */
public class MemoryFact {
    public long id;
    public long contactId;
    public FactCategory category = FactCategory.PERSON;
    public String text = "";
    public String textNorm = "";
    public int importance = 3;           // 1..5
    public double confidence = 0.7;      // 0..1
    public boolean pinned;
    public boolean disabled;
    public Long sourceMessageId;
    public long createdAt;
    public long updatedAt;

    public MemoryFact() { }
}
