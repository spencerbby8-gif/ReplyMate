package com.replymate.app.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.replymate.app.R;
import com.replymate.app.ReplyMateApp;
import com.replymate.app.di.AppContainer;
import com.replymate.app.listener.ListenerStatus;
import com.replymate.app.platform.Tasks;
import com.replymate.core.listener.IngestCoordinator;
import com.replymate.core.util.TimeFmt;
import com.replymate.data.db.DbHealth;
import java.util.List;
import java.util.Set;

/** P21-production §6 — Diagnostics as a REAL dedicated screen (was an inline
 *  accordion on Settings). Everything is computed LIVE on open — never a cached
 *  snapshot — and the report is REDACTED BEFORE it is rendered: the text on
 *  screen is byte-identical to what "Copy diagnostics" puts on the clipboard, so
 *  nothing sensitive is ever one accidental paste away. Redaction covers keys
 *  and tokens (Secrets), known contact/owner names, and long digit runs (phone
 *  numbers / chat ids). Message bodies never enter this pipeline at all.
 *
 *  Sections: database self-test · listener state (enabled / bound / OS gates) ·
 *  per-app parser stats · notification-delivery verdicts (incl. assistant alert
 *  outcomes) · provider health marks + last provider error · recent safe traces. */
public final class DiagnosticsActivity extends Activity {

    private AppContainer c;
    private String report = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);
        c = ReplyMateApp.containerOf(this);
        LinearLayout root = (LinearLayout) findViewById(R.id.diag_root);

        TextView header = Ui.tv(this, "‹ Diagnostics", 22, Ui.PRIMARY);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, 0, 0, Ui.dp(this, 4));
        header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(header);
        root.addView(Ui.sub(this,
            "Everything below is computed right now, on this device. The text is"
            + " already redacted (names, numbers, keys) — exactly what gets copied."
            + " Nothing leaves this phone unless YOU paste it somewhere."));

        Button copy = Ui.btn(this, "Copy diagnostics (safe to share)");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, -2);
        clp.topMargin = Ui.dp(this, 12);
        clp.bottomMargin = Ui.dp(this, 12);
        root.addView(copy, clp);

        final TextView body = Ui.sub(this,
            "computing live state (one moment)…\nThe report is assembled off the"
            + " main thread — database reads and its write probe never run on the UI lane.");
        body.setTextIsSelectable(true);
        root.addView(body);

        // P21-audit R3-1: the report runs DbHealth.check (a real SQLite WRITE
        // probe) plus ~30 kv reads and the contact-name scan — none of that on
        // the UI thread (owner's ANR evidence class). Copy stays disabled until
        // the full redacted report exists.
        copy.setEnabled(false);
        final Button copyBtn = copy;
        Tasks.call(new Tasks.Job<String>() {
            @Override public String run() { return buildReport(); }
        }, new Tasks.Done<String>() {
            @Override public void accept(String text) {
                if (isFinishing()) return;
                report = text;
                body.setText(text);
                copyBtn.setEnabled(true);
            }
        });

        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm =
                    (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (cm == null) {
                    Toast.makeText(DiagnosticsActivity.this,
                        "Clipboard unavailable", Toast.LENGTH_SHORT).show();
                    return;
                }
                cm.setPrimaryClip(ClipData.newPlainText("ReplyMate diagnostics", report));
                Toast.makeText(DiagnosticsActivity.this,
                    "Copied — names, numbers and keys are already redacted",
                    Toast.LENGTH_LONG).show();
            }
        });
    }

    /* -------------------------------------------------------------- assembly */

    /** The full report, redacted via Redactor (names from the contact book +
     *  the owner profile) so the rendered text IS the shareable text. */
    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("ReplyMate diagnostics — ")
          .append(TimeFmt.dayTime(System.currentTimeMillis())).append('\n');
        sb.append("(redacted: names → [name], long numbers → [num], keys masked)\n\n");

        sb.append("== Database ==\n");
        DbHealth.Report r = DbHealth.check(c.db());
        sb.append(r.details()).append('\n');

        sb.append("\n== Listener ==\n");
        sb.append("  enabled in system: ").append(ListenerStatus.isServiceEnabled(this)).append('\n');
        sb.append("  bound right now: ")
          .append(com.replymate.app.listener.RmNotificationListener.active() != null)
          .append("  (enabled but not bound = toggle its access OFF/ON in system settings)\n");
        sb.append("  own notifications allowed (heads-up alerts need this): ")
          .append(ListenerStatus.canPostNotifications(this)).append('\n');
        sb.append("  battery optimization ignored: ").append(ListenerStatus.batteryWhitelisted(this))
          .append("  (false + delivery trouble = set Battery → Unrestricted)\n");
        sb.append("  Android 9+ background restricted: ")
          .append(ListenerStatus.backgroundRestricted(this)).append('\n');
        sb.append("  app standby bucket: ").append(ListenerStatus.standbyBucketName(this))
          .append(ListenerStatus.standbyRestricted(this)
              ? "  ← RESTRICTED: network/jobs heavily limited until it lifts" : "")
          .append('\n');
        sb.append("  power save mode on: ").append(ListenerStatus.powerSaveOn(this))
          .append(" · low-power-standby exempt: ")
          .append(ListenerStatus.lowPowerStandbyExempt(this)).append('\n');
        sb.append("  assistant switch: ")
          .append(com.replymate.app.assistant.AssistantRunner.enabled(c) ? "ON" : "OFF")
          .append('\n');
        sb.append("  ColorOS background switch (unreadable by Android): ")
          .append(com.replymate.app.assistant.AssistantPrereq.isOplus()
              ? (com.replymate.app.assistant.AssistantPrereq.oplusConfirmed(c.kv())
                    ? "owner-confirmed ON ✓ (Android can't read it directly)"
                    : "NOT CONFIRMED — Battery usage page → \"Allow background activity\"")
              : "n/a (not an Oppo/OnePlus/realme device)").append('\n');
        sb.append("  connected at: ").append(tsLine("listener.connected_at")).append('\n');
        sb.append("  disconnected at: ").append(tsLine("listener.disconnected_at")).append('\n');
        sb.append("  last rebind requested: ")
          .append(tsLine(com.replymate.core.listener.RebindPolicy.KV_LAST_ATTEMPT)).append('\n');
        sb.append("  last background pipeline: ")
          .append(c.kv().get(com.replymate.app.assistant.AssistantRunner.KV_LATENCY_LAST,
              "no completed generation yet")).append('\n');
        sb.append("  watch whatsapp: ").append(c.kv().get("watch.whatsapp", "1"))
          .append(" · telegram: ").append(c.kv().get("watch.telegram", "1")).append('\n');
        sb.append("  messages stored (listener): ")
          .append(c.kv().get(IngestCoordinator.KV_STORED_TOTAL, "0")).append('\n');
        sb.append("  parse errors: ").append(c.kv().get("listener.parse_errors", "0"))
          .append(" · unparsed (non-chat) notifs: ")
          .append(c.kv().get("listener.unparsed_total", "0")).append('\n');
        sb.append("  last event: ").append(tsLine(IngestCoordinator.KV_LAST_EVENT)).append('\n');

        sb.append("\n== Per-app parser stats ==\n");
        sb.append(perAppStats());

        sb.append("\n== Notification delivery (alert verdicts, newest first) ==\n");
        // P21 §1/§6: every draft-notification decision is recorded with its reason
        // (fresh heads-up / silent refresh / suppressed-by-rule / blocked). This is
        // the "why didn't the banner show" evidence trail.
        java.util.List<String> aLines =
            com.replymate.app.assistant.AssistantDiag.lines(c.kv(), 10);
        if (aLines.isEmpty()) {
            sb.append("  (no assistant events recorded yet)\n");
        } else {
            for (String l : aLines) sb.append("  ").append(l).append('\n');
        }

        sb.append("\n== AI providers ==\n");
        sb.append(providerLines());
        String lastErr = c.kv().get(LastProviderError.KV_KEY, "");
        sb.append("  last provider error:\n");
        sb.append(lastErr.isEmpty() ? "    (none recorded)\n" : indent(lastErr, 4));

        sb.append("\n== Listener boundary trace (newest first) ==\n");
        java.util.List<String> traceLines =
            com.replymate.core.listener.ListenerTrace.lines(c.kv());
        if (traceLines.isEmpty()) {
            sb.append("  (none yet)\n");
        } else {
            for (String tl : traceLines) {
                int tab = tl.indexOf('\t');
                long ts = tab > 0 ? parseLong(tl.substring(0, tab)) : 0;
                String text = tab > 0 ? tl.substring(tab + 1) : tl;
                sb.append("  ").append(ts > 0 ? TimeFmt.dayTime(ts) : "?")
                  .append(" — ").append(text).append('\n');
            }
        }

        sb.append("\n== Recent events (newest first) ==\n");
        List<String> ringLines =
            com.replymate.core.listener.DiagnosticsRing.lines(
                c.kv().get(IngestCoordinator.KV_RING, ""));
        if (ringLines.isEmpty()) {
            sb.append("  (none yet)\n");
        } else {
            for (String l : ringLines) {
                int tab = l.indexOf('\t');
                long ts = tab > 0 ? parseLong(l.substring(0, tab)) : 0;
                String text = tab > 0 ? l.substring(tab + 1) : l;
                sb.append("  ").append(ts > 0 ? TimeFmt.dayTime(ts) : "?")
                  .append(" — ").append(text).append('\n');
            }
        }
        return redact(sb.toString());
    }

    /** Per-app stats for apps actually installed (not-installed apps can't emit
     *  notifications to count — listed once, marked as such). */
    private String perAppStats() {
        StringBuilder sb = new StringBuilder();
        com.replymate.core.listener.ListenerStats stats =
            new com.replymate.core.listener.ListenerStats(c.kv());
        java.util.LinkedHashSet<com.replymate.core.model.Channel> ordered =
            new java.util.LinkedHashSet<com.replymate.core.model.Channel>();
        for (com.replymate.core.listener.WatchedApps.AppDef def
                : com.replymate.core.listener.WatchedApps.all()) {
            ordered.add(def.channel);
        }
        java.util.Set<com.replymate.core.model.Channel> enabled =
            com.replymate.core.listener.ParserRegistry.enabledFromKv(c.kv(),
                java.util.EnumSet.of(com.replymate.core.model.Channel.WHATSAPP,
                    com.replymate.core.model.Channel.TELEGRAM));
        StringBuilder notInstalled = new StringBuilder();
        int installed = 0;
        for (com.replymate.core.model.Channel ch : ordered) {
            boolean has = false;
            for (String p : com.replymate.core.listener.WatchedApps.packagesFor(ch)) {
                has |= com.replymate.app.platform.DeepLinks.packageExists(this, p);
            }
            if (!has) {
                if (notInstalled.length() > 0) notInstalled.append(", ");
                notInstalled.append(com.replymate.core.listener.WatchedApps.labelFor(ch));
                continue;
            }
            installed++;
            sb.append("  ").append(ch.wire)
              .append(enabled.contains(ch) ? " [on]" : " [off]").append(": ")
              .append("recv ").append(stats.receivedOf(ch))
              .append(" · parsed ").append(stats.parsedOf(ch))
              .append(" · ignored ").append(stats.ignoredOf(ch))
              .append(" · failed ").append(stats.failedOf(ch));
            String reason = stats.lastReasonOf(ch);
            if (reason != null && !reason.isEmpty()) {
                sb.append(" · last: ").append(reason);
            }
            sb.append('\n');
        }
        StringBuilder out = new StringBuilder();
        out.append("  installed supported apps: ").append(installed)
           .append(" of ").append(ordered.size()).append('\n');
        out.append(sb);
        if (notInstalled.length() > 0) {
            out.append("  not installed: ").append(notInstalled).append('\n');
        }
        return out.toString();
    }

    /** Per-provider MODEL-SCOPED health marks (P21 §7) — never "configured", only
     *  what a real probe verdict says, and it says which model earned it. */
    private String providerLines() {
        StringBuilder sb = new StringBuilder();
        java.util.List<com.replymate.core.model.ProviderDef> defs = c.providers().all();
        if (defs.isEmpty()) return "  (no providers configured)\n";
        for (com.replymate.core.model.ProviderDef d : defs) {
            String label = d.label.isEmpty() ? d.type.label : d.label;
            sb.append("  ").append(label).append(" · ")
              .append(d.modelName.isEmpty() ? "no model selected" : d.modelName)
              .append(d.isActive ? "  (ACTIVE)" : "").append('\n');
            com.replymate.core.provider.ProviderHealth.Mark m =
                com.replymate.core.provider.ProviderHealth.decode(c.kv().get(
                    com.replymate.core.provider.ProviderHealth.keyFor(d.type.wire), ""));
            if (m == null) {
                sb.append("    health: never tested\n");
            } else {
                String when = m.millis > 0 ? " at " + TimeFmt.dayTime(m.millis) : "";
                sb.append("    health: ").append(m.ok ? "OK ✓" : "LAST TEST FAILED")
                  .append(m.model.isEmpty() ? " (model not recorded — re-test)"
                      : " for \"" + m.model + "\"")
                  .append(m.ok || m.model.isEmpty() || m.model.equals(d.modelName)
                      ? "" : "  ← different model than selected, re-test")
                  .append(when).append('\n');
            }
        }
        return sb.toString();
    }

    private String indent(String text, int spaces) {
        String pad = "                                ".substring(0, spaces);
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) sb.append(pad).append(line).append('\n');
        return sb.toString();
    }

    private String redact(String raw) {
        Set<String> names = new java.util.HashSet<String>();
        try {
            for (com.replymate.core.model.Contact ct : c.contacts().all()) {
                if (ct.displayName != null && !ct.displayName.trim().isEmpty()) {
                    names.add(ct.displayName.trim());
                }
            }
        } catch (RuntimeException ignored) { }
        try {
            com.replymate.core.usecase.ProfileService.Profile p = c.profiles().load();
            if (p != null && p.displayName() != null && !p.displayName().trim().isEmpty()) {
                names.add(p.displayName().trim());
            }
        } catch (RuntimeException ignored) { }
        return com.replymate.core.privacy.Redactor.redactForSharing(raw, names);
    }

    private String tsLine(String key) {
        long ts = parseLong(c.kv().get(key, ""));
        return ts > 0 ? TimeFmt.dayTime(ts) : "—";
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }
}
