package com.replymate.core.model;

/** One billed/tracked AI call for the usage & budget dashboard. */
public class UsageEvent {
    public long id;
    public long ts;
    public String model = "";
    public int tokensIn;
    public int tokensOut;
    public UsageKind kind = UsageKind.REPLY;

    public UsageEvent() { }
}
