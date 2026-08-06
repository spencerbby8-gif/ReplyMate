package com.replymate.core.model;

/** A single message in a contact's isolated log. notifKey == null for manual rows. */
public class Message {
    public long id;
    public long contactId;
    public Channel channel = Channel.MANUAL;
    public Direction direction = Direction.INCOMING;
    public String body = "";
    public long sentAt;
    public String notifKey;              // dedupe key (listener only)
    public Source source = Source.MANUAL;

    public Message() { }
}
