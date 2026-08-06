package com.replymate.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.replymate.app.R;
import com.replymate.app.ReplyMateApp;
import com.replymate.app.di.AppContainer;
import com.replymate.app.listener.ListenerStatus;
import com.replymate.core.listener.DiagnosticsRing;
import com.replymate.core.listener.IngestCoordinator;
import com.replymate.core.util.TimeFmt;
import com.replymate.data.db.DbHealth;
import java.util.List;

/** S13 — Settings hub (P2): profile, provider, listening controls, diagnostics, about. */
public final class SettingsActivity extends Activity {

    private static final int REQ_POST_NOTIF = 42;

    private AppContainer c;
    private LinearLayout root;
    private LinearLayout providerRow, listenRow, waRow, tgRow, notifRow;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        c = ReplyMateApp.containerOf(this);
        root = (LinearLayout) findViewById(R.id.settings_root);

        TextView header = Ui.tv(this, "‹ Settings", 22, Ui.PRIMARY);
        header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.setPadding(0, 0, 0, Ui.dp(this, 12));
        header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(header);

        LinearLayout profileRow = Ui.row(this, "My profile", "name, languages, bio, topics");
        profileRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, ProfileActivity.class));
            }
        });
        root.addView(profileRow);
        root.addView(Ui.divider(this));

        providerRow = Ui.row(this, "AI provider (Gemini)", "");
        providerRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, ProviderActivity.class));
            }
        });
        root.addView(providerRow);
        root.addView(Ui.divider(this));

        listenRow = Ui.row(this, "Message listening", "");
        listenRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ListenerStatus.openAccessSettings(SettingsActivity.this);
            }
        });
        root.addView(listenRow);
        root.addView(Ui.divider(this));

        LinearLayout sourcesRow = Ui.row(this, "Notification sources",
            "choose which messaging apps ReplyMate may read");
        sourcesRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, SourcesActivity.class));
            }
        });
        root.addView(sourcesRow);
        root.addView(Ui.divider(this));

        waRow = Ui.row(this, "Watch WhatsApp", "");
        waRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle("watch.whatsapp"); }
        });
        root.addView(waRow);

        tgRow = Ui.row(this, "Watch Telegram", "");
        tgRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { toggle("watch.telegram"); }
        });
        root.addView(tgRow);
        root.addView(Ui.divider(this));

        notifRow = Ui.row(this, "ReplyMate notifications", "");
        notifRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // NEVER a dead row: request the runtime grant on 33+ when missing,
                // otherwise open the real system notification settings for this app.
                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                            != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                        new String[] {"android.permission.POST_NOTIFICATIONS"}, REQ_POST_NOTIF);
                } else {
                    openSystemNotificationSettings();
                }
            }
        });
        root.addView(notifRow);
        root.addView(Ui.divider(this));

        LinearLayout usageRow = Ui.row(this, "Usage dashboard",
            "local metering of every AI call (count + tokens)");
        usageRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, UsageActivity.class));
            }
        });
        root.addView(usageRow);
        root.addView(Ui.divider(this));

        LinearLayout diagRow = Ui.row(this, "Diagnostics", "database + listener self-test, per-app stats, recent events");
        root.addView(diagRow);
        final TextView diagDetails = Ui.sub(this, "");
        diagDetails.setVisibility(View.GONE);
        diagDetails.setPadding(0, 0, 0, Ui.dp(this, 8));
        root.addView(diagDetails);
        diagRow.setOnClickListener(new View.OnClickListener() {
            boolean open;
            @Override public void onClick(View v) {
                open = !open;
                if (open) {
                    diagDetails.setText(buildDiagnostics());
                    diagDetails.setVisibility(View.VISIBLE);
                } else {
                    diagDetails.setVisibility(View.GONE);
                }
            }
        });
        root.addView(Ui.divider(this));

        LinearLayout aboutRow = Ui.row(this, "About", buildAboutLine());
        root.addView(aboutRow);
        final TextView aboutDetails = Ui.sub(this, "");
        aboutDetails.setVisibility(View.GONE);
        aboutDetails.setPadding(0, 0, 0, Ui.dp(this, 8));
        root.addView(aboutDetails);
        aboutRow.setOnClickListener(new View.OnClickListener() {
            boolean open;
            @Override public void onClick(View v) {
                open = !open;
                if (open) {
                    aboutDetails.setText(buildAboutDetails());
                    aboutDetails.setVisibility(View.VISIBLE);
                } else {
                    aboutDetails.setVisibility(View.GONE);
                }
            }
        });
        root.addView(Ui.divider(this));

        TextView footer = Ui.sub(this, "\nReplyMate only READS notifications you allow. It never sends,\nauto-replies, or syncs anything off this phone.");
        root.addView(footer);
    }

    /** About row text — reflects the shipped build, not a hardcoded string. */
    private String buildAboutLine() {
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                .getPackageInfo(getPackageName(), 0);
            long vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            return "ReplyMate " + pi.versionName + " (" + vc + ") · read-only listening · no sending";
        } catch (PackageManager.NameNotFoundException nnf) {
            return "ReplyMate · read-only listening · no sending";
        }
    }

    /** Expanded About panel (real action for the row): build + runtime truth. */
    private String buildAboutDetails() {
        StringBuilder sb = new StringBuilder();
        try {
            android.content.pm.PackageInfo pi = getPackageManager()
                .getPackageInfo(getPackageName(), 0);
            long vc = Build.VERSION.SDK_INT >= 28 ? pi.getLongVersionCode() : pi.versionCode;
            sb.append("version: ").append(pi.versionName).append(" (").append(vc).append(")\n");
        } catch (PackageManager.NameNotFoundException nnf) {
            sb.append("version: unknown\n");
        }
        sb.append("min sdk: 24 · target sdk: ").append(getApplicationInfo().targetSdkVersion)
          .append(" · device sdk: ").append(Build.VERSION.SDK_INT).append('\n');
        sb.append("signer: ").append(signerDigest()).append('\n');
        sb.append("local db: replymate.db (schema v")
          .append(com.replymate.data.db.Migrations.LATEST).append(") · backups: off\n");
        sb.append("listener service: ")
          .append(ListenerStatus.isServiceEnabled(this) ? "enabled" : "not enabled").append('\n');
        sb.append("cloud sync: OFF (local-first)\n");
        sb.append("watch flags: ").append(watchSummary());
        return sb.toString();
    }

    private String signerDigest() {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                android.content.pm.PackageInfo pi = getPackageManager().getPackageInfo(
                    getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                android.content.pm.Signature[] sigs = pi.signingInfo.getApkContentsSigners();
                if (sigs != null && sigs.length > 0) {
                    java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                    byte[] d = md.digest(sigs[0].toByteArray());
                    StringBuilder hex = new StringBuilder();
                    for (int i = 0; i < 8 && i < d.length; i++) {
                        hex.append(String.format(java.util.Locale.US, "%02x", d[i]));
                    }
                    return "sha256 " + hex + "…";
                }
            }
        } catch (Exception e) { /* fall through */ }
        return "unavailable";
    }

    /** The channels whose watch flag is effectively ON (real kv state). */
    private String watchSummary() {
        java.util.Set<com.replymate.core.model.Channel> enabled =
            com.replymate.core.listener.ParserRegistry.enabledFromKv(
                c.kv(), java.util.EnumSet.of(
                    com.replymate.core.model.Channel.WHATSAPP,
                    com.replymate.core.model.Channel.TELEGRAM));
        if (enabled.isEmpty()) return "none (all off)";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (com.replymate.core.model.Channel ch : com.replymate.core.model.Channel.values()) {
            if (!enabled.contains(ch)) continue;
            if (!first) sb.append(", ");
            sb.append(ch.wire);
            first = false;
        }
        return sb.toString();
    }

    /** Real system screens for our own notification settings. */
    private void openSystemNotificationSettings() {
        Intent i = new Intent();
        if (Build.VERSION.SDK_INT >= 26) {
            i.setAction("android.settings.APP_NOTIFICATION_SETTINGS");
            i.putExtra("android.provider.extra.APP_PACKAGE", getPackageName());
        } else {
            i.setAction("android.settings.APPLICATION_DETAILS_SETTINGS");
            i.setData(android.net.Uri.parse("package:" + getPackageName()));
        }
        try {
            startActivity(i);
        } catch (RuntimeException boom) {
            Toast.makeText(this, "No settings screen available on this device",
                Toast.LENGTH_SHORT).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refreshRows();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_POST_NOTIF) refreshRows();
    }

    private void toggle(String key) {
        String cur = c.kv().get(key, "1");
        c.kv().put(key, "1".equals(cur) ? "0" : "1");
        refreshRows();
    }

    private void refreshRows() {
        if (c == null) return;
        Ui.setRowSub(providerRow, c.providerOrNull() != null
            ? "configured ✓ (" + String.valueOf(c.activeModelOrNull()) + ")"
            : "not configured — add your API key");
        Ui.setRowSub(listenRow, ListenerStatus.isServiceEnabled(this)
            ? "enabled ✓ (read-only)" : "off — tap to open system settings");
        Ui.setRowSub(waRow, "1".equals(c.kv().get("watch.whatsapp", "1")) ? "on" : "off");
        Ui.setRowSub(tgRow, "1".equals(c.kv().get("watch.telegram", "1")) ? "on" : "off");
        Ui.setRowSub(notifRow, ListenerStatus.canPostNotifications(this)
            ? "allowed ✓ — ReplyMate can alert you about new messages"
            : "blocked — tap to allow (alerts stay silent otherwise)");
    }

    private String buildDiagnostics() {
        StringBuilder sb = new StringBuilder();
        DbHealth.Report r = DbHealth.check(c.db());
        sb.append("Database:\n").append(r.details()).append('\n');
        sb.append("Listener:\n");
        sb.append("  enabled in system: ").append(ListenerStatus.isServiceEnabled(this)).append('\n');
        sb.append("  own notifications: ").append(ListenerStatus.canPostNotifications(this)).append('\n');
        sb.append("  connected at: ").append(tsLine("listener.connected_at")).append('\n');
        sb.append("  disconnected at: ").append(tsLine("listener.disconnected_at")).append('\n');
        sb.append("  watch whatsapp: ").append(c.kv().get("watch.whatsapp", "1"))
          .append(" · telegram: ").append(c.kv().get("watch.telegram", "1")).append('\n');
        sb.append("  messages stored (listener): ")
          .append(c.kv().get(IngestCoordinator.KV_STORED_TOTAL, "0")).append('\n');
        sb.append("Per-app listener stats:\n");
        sb.append(perAppStats()).append('\n');
        sb.append("  last event: ").append(tsLine(IngestCoordinator.KV_LAST_EVENT)).append('\n');
        sb.append("Cloud (foundation):\n");
        sb.append("  endpoint: ").append(com.replymate.core.supabase.SupabaseConfig.PROJECT_URL).append('\n');
        sb.append("  tables provisioned: ")
          .append(com.replymate.core.supabase.SupabaseConfig.TABLES.length)
          .append(" · sync: ").append(com.replymate.core.supabase.SupabaseConfig.SYNC_ENABLED ? "ON" : "OFF (local-first)")
          .append('\n');
        sb.append("  parse errors: ").append(c.kv().get("listener.parse_errors", "0"))
          .append(" · unparsed (non-chat) notifs: ").append(c.kv().get("listener.unparsed_total", "0"))
          .append('\n');
        sb.append("Recent events (newest first):\n");
        List<String> lines = DiagnosticsRing.lines(c.kv().get(IngestCoordinator.KV_RING, ""));
        if (lines.isEmpty()) {
            sb.append("  (none yet)");
        } else {
            for (String l : lines) {
                int tab = l.indexOf('\t');
                long ts = tab > 0 ? parseLong(l.substring(0, tab)) : 0;
                String text = tab > 0 ? l.substring(tab + 1) : l;
                sb.append("  ").append(ts > 0 ? TimeFmt.dayTime(ts) : "?").append(" — ")
                  .append(text).append('\n');
            }
        }
        return sb.toString();
    }

    /** Per-app stats ONLY for apps really installed on this device (detection via
     *  PackageManager; not-installed apps can't emit notifications to count). */
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
            com.replymate.core.listener.ParserRegistry.enabledFromKv(
                c.kv(), java.util.EnumSet.of(
                    com.replymate.core.model.Channel.WHATSAPP,
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

    private String tsLine(String key) {
        long ts = parseLong(c.kv().get(key, ""));
        return ts > 0 ? TimeFmt.dayTime(ts) : "—";
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }
}
