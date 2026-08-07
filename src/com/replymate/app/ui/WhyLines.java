package com.replymate.app.ui;

import java.util.ArrayList;
import java.util.List;

/** Shared "why" extractor (P-polish): reads the immutable per-draft audit snapshot
 *  and returns the recorded decision lines — which voice settings, contact overrides,
 *  custom instructions and learning signals shaped that exact reply. Used by the
 *  conversation cards ("Why this reply?") and the Prompt audit screen. */
public final class WhyLines {

    private WhyLines() { }

    /** The snapshot's "why" array, exactly as recorded at generation time. */
    public static List<String> from(String snapshotJson) {
        List<String> out = new ArrayList<String>();
        if (snapshotJson == null || snapshotJson.isEmpty()) return out;
        try {
            Object raw = com.replymate.core.json.Json.parseObj(snapshotJson).raw("why");
            if (raw instanceof List) {
                for (Object o : (List<?>) raw) {
                    if (o != null) {
                        String line = String.valueOf(o).trim();
                        if (!line.isEmpty()) out.add(line);
                    }
                }
            }
        } catch (RuntimeException ignore) {
            // legacy/corrupt snapshot — the panel stays quiet, never crashes the card
        }
        return out;
    }
}
