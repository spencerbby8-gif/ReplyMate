package com.replymate.core.listener;

import com.replymate.core.json.Json;
import com.replymate.core.json.JsonArr;
import com.replymate.core.json.JsonObj;
import java.util.ArrayList;
import java.util.List;

/** Capped ring of recent listener events, persisted as one JSON value in app_kv
 *  (developer diagnostics — no schema change). Stored newest-first. */
public final class DiagnosticsRing {

    public static final int CAP = 12;

    private DiagnosticsRing() { }

    public static String append(String ringJson, long ts, String line) {
        JsonArr arr = parse(ringJson);
        JsonArr next = JsonArr.create();
        // P-background-11: the ring persists — redact secret shapes at the single
        // choke point so no caller can ever leak one into durable storage.
        next.add(JsonObj.create().put("ts", ts).put("line",
            com.replymate.core.privacy.Secrets.redact(line)));
        int kept = 0;
        for (int i = 0; i < arr.size() && kept < CAP - 1; i++) {
            JsonObj o = arr.obj(i);
            if (o == null) continue;
            next.add(o);
            kept++;
        }
        return next.toJson();
    }

    /** Display lines, newest first: "HH:mm line" would need tz; caller formats ts itself
     *  if needed — we just return "ts|line" pairs joined as "ts\tline" list entries. */
    public static List<String> lines(String ringJson) {
        List<String> out = new ArrayList<String>();
        JsonArr arr = parse(ringJson);
        for (int i = 0; i < arr.size(); i++) {
            JsonObj o = arr.obj(i);
            if (o == null) continue;
            out.add(o.lng("ts", 0) + "\t" + o.str("line", ""));
        }
        return out;
    }

    private static JsonArr parse(String ringJson) {
        if (ringJson == null || ringJson.trim().isEmpty()) return JsonArr.create();
        try {
            return Json.parseArr(ringJson);
        } catch (RuntimeException ignored) { }
        return JsonArr.create();
    }
}
