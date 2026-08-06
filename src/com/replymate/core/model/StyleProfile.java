package com.replymate.core.model;

/** Writing-style rules. GLOBAL scope = owner's default voice; CONTACT scope = per-contact override. */
public class StyleProfile {
    public long id;
    public Scope scope = Scope.GLOBAL;
    public Long contactId;               // non-null only when scope == CONTACT
    public String sampleMessages = "";
    public String derivedRules = "";
    public long updatedAt;

    public StyleProfile() { }
}
