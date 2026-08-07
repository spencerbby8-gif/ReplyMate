package com.replymate.core.assistant;

import com.replymate.core.json.Json;
import com.replymate.core.json.JsonObj;
import java.util.LinkedHashMap;
import java.util.Map;

/** P-background-2: one structured assistant diagnostics record. Owner's mandate:
 *  EVERY assistant failure records conversation, provider, model, notification id,
 *  the pipeline stage that failed, the exact reason, the action taken and a
 *  suggested fix. Pure JVM (JSON round-trip via core Json) so the shape is pinned. */
public final class AssistantEvent {

    /** Pipeline stages (wire strings are stable — Diagnostics parses them). */
    public enum Stage {
        SCHEDULE("schedule"),             // debounce/coalescing decision
        GATES("gates"),                   // private/AI-off/permission/no-provider checks
        GENERATE("generate"),             // provider request + draft persistence
        NOTIFY("notify"),                 // posting/updating the ReplyMate alert
        APPROVE_RESOLVE("approve_resolve"),// re-resolving the live quick-reply target
        REMOTE_SEND("remote_send"),       // RemoteInput results → action PendingIntent
        REGEN("regen"),                   // regenerate-tap handling
        COPY_FALLBACK("copy_fallback");   // honest clipboard fallback

        public final String wire;
        Stage(String w) { this.wire = w; }

        public static Stage fromWire(String w) {
            for (Stage s : values()) if (s.wire.equals(w)) return s;
            return GENERATE;
        }
    }

    public long ts;
    public long contactId;
    public String contactName = "";
    public String provider = "";          // "" when none configured
    public String model = "";
    public String alertTag = "";          // our ReplyMate alert identity (tag+id)
    public String sbnKey = "";            // source notification key (short)
    public Stage stage = Stage.GENERATE;
    public String reason = "";            // exact cause
    public String action = "";            // what ReplyMate did about it
    public String fix = "";               // suggested next step, human words

    public AssistantEvent() { }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ts", Long.valueOf(ts));
        m.put("contactId", Long.valueOf(contactId));
        m.put("contact", contactName);
        m.put("provider", provider);
        m.put("model", model);
        m.put("alert", alertTag);
        m.put("sbn", sbnKey);
        m.put("stage", stage.wire);
        m.put("reason", reason);
        m.put("action", action);
        m.put("fix", fix);
        return m;
    }

    public String toJson() {
        return Json.write(toMap());
    }

    public static AssistantEvent fromJson(String s) {
        AssistantEvent e = new AssistantEvent();
        try {
            JsonObj o = Json.parseObj(s);
            e.ts = o.lng("ts", 0L);
            e.contactId = o.lng("contactId", -1L);
            e.contactName = o.str("contact", "");
            e.provider = o.str("provider", "");
            e.model = o.str("model", "");
            e.alertTag = o.str("alert", "");
            e.sbnKey = o.str("sbn", "");
            e.stage = Stage.fromWire(o.str("stage", ""));
            e.reason = o.str("reason", "");
            e.action = o.str("action", "");
            e.fix = o.str("fix", "");
        } catch (RuntimeException corrupt) {
            // a corrupt record still returns a readable shell, never throws
            e.reason = "(unreadable record)";
        }
        return e;
    }

    /** One compact line for Diagnostics rendering. */
    public String line() {
        String who = contactName.isEmpty() ? "#" + contactId : contactName;
        String pm = provider.isEmpty() ? "no provider" : provider + "/" + model;
        return "[" + stage.wire + "] " + who + " · " + pm + " · " + reason
            + " → " + action + (fix.isEmpty() ? "" : " · fix: " + fix);
    }
}
