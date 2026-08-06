package com.replymate.core.model;

/** One app-identity of a contact (e.g. the same person on WhatsApp AND Telegram).
 *  remoteKey is the normalized sender/thread id from the listening channel. */
public class ContactChannel {
    public long id;
    public long contactId;
    public Channel channel = Channel.MANUAL;
    public String remoteKey = "manual";
    public long lastSeenAt;

    public ContactChannel() { }
}
