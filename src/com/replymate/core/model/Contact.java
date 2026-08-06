package com.replymate.core.model;

/** A person the owner talks to. Invariant (enforced by use-case layer):
 *  privateMode == true  =>  aiEnabled == false  (never appears in API payloads). */
public class Contact {
    public long id;
    public String displayName = "";
    public String relationshipType = "";
    public String relationshipNotes = "";
    public String toneOverride = "";
    public String languagePref = "";
    public Long styleProfileId;          // null until P5 style overrides
    public boolean aiEnabled = true;
    public boolean memoryEnabled = true;
    public boolean privateMode = false;
    public long createdAt;
    public long updatedAt;

    public Contact() { }
}
