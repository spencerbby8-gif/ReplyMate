package com.replymate.core.model;

/** Versioned rolling summary of one contact's history (P4). Latest version is used. */
public class ContactSummary {
    public long id;
    public long contactId;
    public String summaryText = "";
    public long coversUntilTs;           // messages up to this ts are summarized
    public int version;
    public long createdAt;

    public ContactSummary() { }
}
