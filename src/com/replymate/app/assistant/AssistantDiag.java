package com.replymate.app.assistant;

import com.replymate.app.di.AppContainer;
import com.replymate.core.assistant.AssistantEvent;
import com.replymate.core.json.Json;
import com.replymate.core.json.JsonArr;
import com.replymate.core.model.ProviderDef;
import com.replymate.core.ports.KvStore;
import java.util.ArrayList;
import java.util.List;

/** P-background-2: bounded, structured assistant failure ring (kv, JSON array,
 *  newest-last, cap 24). Every silent assistant decision lands here with the
 *  owner-mandated fields — nothing about the assistant is ever "just not working"
 *  without this record saying exactly where and why. */
public final class AssistantDiag {

    public static final String KV_KEY = "assistant.diag.v1";
    private static final int MAX = 24;

    private AssistantDiag() {
    }

    public static synchronized void record(AppContainer c, long contactId, String name,
                                           String alertTag, String sbnKey,
                                           AssistantEvent.Stage stage, String reason,
                                           String action, String fix) {
        try {
            AssistantEvent e = new AssistantEvent();
            e.ts = c.clock().now();
            e.contactId = contactId;
            e.contactName = name == null ? "" : name;
            ProviderDef active = c.providers().active();
            if (active != null) {
                e.provider = (active.label == null || active.label.isEmpty())
                    ? active.type.label : active.label;
                e.model = active.modelName == null ? "" : active.modelName;
            } else {
                e.provider = "";
                e.model = "";
            }
            e.alertTag = alertTag == null ? "" : alertTag;
            e.sbnKey = shortKey(sbnKey);
            e.stage = stage;
            // P-background-11: the ledger is DURABLE — every free-text field passes
            // the secret redactor before it can persist. (Provider reasons can
            // quote raw error bodies; a key shape must never survive that.)
            e.reason = com.replymate.core.privacy.Secrets.redact(reason == null ? "?" : reason);
            e.action = com.replymate.core.privacy.Secrets.redact(action == null ? "" : action);
            e.fix = com.replymate.core.privacy.Secrets.redact(fix == null ? "" : fix);

            String existing = c.kv().get(KV_KEY, "");
            List<Object> events = readAll(existing);
            events.add(e.toMap());
            while (events.size() > MAX) events.remove(0);
            c.kv().put(KV_KEY, Json.write(events));
            c.logger().w("AssistantDiag", e.line());
        } catch (RuntimeException ignored) {
            // Diagnostics must never break the pipeline itself.
        }
    }

    /** Formatted lines, LATEST first, for Settings → Diagnostics. */
    public static List<String> lines(KvStore kv, int max) {
        List<String> out = new ArrayList<String>();
        List<Object> all = readAll(kv == null ? "" : kv.get(KV_KEY, ""));
        for (int i = all.size() - 1; i >= 0 && out.size() < max; i--) {
            Object raw = all.get(i);
            AssistantEvent e = AssistantEvent.fromJson(Json.write(raw));
            out.add(e.line());
        }
        return out;
    }

    public static int size(KvStore kv) {
        return readAll(kv == null ? "" : kv.get(KV_KEY, "")).size();
    }

    private static List<Object> readAll(String json) {
        List<Object> out = new ArrayList<Object>();
        try {
            if (json == null || json.trim().isEmpty()) return out;
            JsonArr arr = Json.parseArr(json);
            for (int i = 0; i < arr.size(); i++) {
                out.add(arr.raw(i));
            }
        } catch (RuntimeException corrupted) {
            // start fresh — never parse-crash the pipeline
        }
        return out;
    }

    private static String shortKey(String k) {
        if (k == null) return "";
        return k.length() <= 24 ? k : "…" + k.substring(k.length() - 24);
    }
}
