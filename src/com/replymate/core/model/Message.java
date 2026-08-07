package com.replymate.core.model;

/** A single message in a contact's isolated log. notifKey == null for manual rows.
 *  contentKind: WHAT the item is (schema v5), detected from notification evidence
 *  only — never from the source app; mediaMime/mediaUri: the attachment reference
 *  captured locally (never opened, never uploaded). Legacy rows have contentKind ""
 *  — effectiveKind() infers from the stored body shape. */
public class Message {
    public long id;
    public long contactId;
    public Channel channel = Channel.MANUAL;
    public Direction direction = Direction.INCOMING;
    public String body = "";
    public long sentAt;
    public String notifKey;              // dedupe key (listener only)
    public Source source = Source.MANUAL;
    public String contentKind = "";      // ContentKind.wire, "" = legacy/not recorded
    public String mediaMime = "";        // e.g. "image/jpeg" when the app exposed it
    public String mediaUri = "";         // content:// reference (local-only, never sent)

    public Message() { }

    /** The effective content kind: explicit when recorded (0.9.0+), inferred from the
     *  stored body shape for legacy rows. Never null. */
    public ContentKind effectiveKind() {
        ContentKind k = ContentKind.fromWire(contentKind);
        if (k != null) return k;
        ContentKind legacy = ContentKind.fromBodyShape(body);
        return legacy == null ? ContentKind.TEXT : legacy;
    }
}
