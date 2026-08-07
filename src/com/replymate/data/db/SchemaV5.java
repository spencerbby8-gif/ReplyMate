package com.replymate.data.db;

import java.util.Arrays;
import java.util.List;

/** Schema v5 (P-audit-deep media/identity pipeline): the message table gains the
 *  evidence captured at ingest so content handling stays honest end-to-end:
 *    content_type — WHAT the item is (text/image/video/audio/voice/sticker/call/
 *                   unknown), detected from notification evidence only, never from
 *                   which app sent it; "" = legacy row (inferred from body shape);
 *    media_mime   — attachment MIME when the posting app exposed it (MessagingStyle
 *                   "type"/dataMimeType); never synthesized;
 *    media_uri    — attachment content reference (MessagingStyle "uri"/dataUri),
 *                   stored as a LOCAL pointer only: never opened, never uploaded.
 *  Existing rows keep their bodies; their kind is inferred by effectiveKind() —
 *  no data rewrite, nothing lost. */
public final class SchemaV5 {

    private SchemaV5() { }

    public static final List<String> DDL = Arrays.asList(
        "ALTER TABLE message ADD COLUMN content_type TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE message ADD COLUMN media_mime TEXT NOT NULL DEFAULT ''",
        "ALTER TABLE message ADD COLUMN media_uri TEXT NOT NULL DEFAULT ''"
    );
}
