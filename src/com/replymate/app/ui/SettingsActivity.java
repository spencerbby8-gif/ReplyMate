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
    private LinearLayout providerRow, listenRow, notifRow, accountRow;
    private Ui.ToggleRow assistantRow;

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

        accountRow = Ui.row(this, "Account", "");
        accountRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Intent i = new Intent(SettingsActivity.this, AuthActivity.class);
                i.putExtra(AuthActivity.EXTRA_MODE, "link");
                startActivity(i);
            }
        });
        root.addView(accountRow);
        root.addView(Ui.divider(this));

        LinearLayout voiceRow = Ui.row(this, "My voice (global style)",
            "tone, length, emoji, humor… the base style for every chat");
        voiceRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, VoiceActivity.class));
            }
        });
        root.addView(voiceRow);
        root.addView(Ui.divider(this));

        providerRow = Ui.row(this, "AI providers", "");
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

        // P-background: master switch for the assistant. Real switch, live state,
        // honest subtitle (permission prerequisite + human-approval contract).
        assistantRow = Ui.toggleRow(this, "Background reply assistant",
            "drafts a reply when a watched chat pings — you approve every send");
        assistantRow.sw.setChecked(
            com.replymate.app.assistant.AssistantRunner.enabled(c));
        assistantRow.sw.setOnCheckedChangeListener(
            new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    c.kv().put(
                        com.replymate.app.assistant.AssistantRunner.KV_ENABLED, on ? "1" : "0");
                    // P-background-6: toggling ON drives Android's proper approval
                    // flow (notification access / show-notifications / battery),
                    // each explained; the subtitle keeps any gap honest.
                    if (on) runAssistantApprovalFlow();
                    refreshRows();
                }
            });
        assistantRow.row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // blocked-state re-entry: tap the row itself to re-run the flow
                if (com.replymate.app.assistant.AssistantRunner.enabled(c)
                        && !com.replymate.app.assistant.AssistantPrereq
                            .missing(SettingsActivity.this).isEmpty()) {
                    runAssistantApprovalFlow();
                }
            }
        });
        root.addView(assistantRow.row);
        root.addView(Ui.divider(this));

        // P-intelligence-4 (live context): device clock + dated glossary toggle.
        // Honest subtitle: everything here is ON-DEVICE — a clock read and a small
        // curated word list, never a web lookup, and generation works fine with it off.
        Ui.ToggleRow liveRow = Ui.toggleRow(this, "Live context (date & time + slang)",
            "adds this phone's real date/time/timezone — plus a small dated slang"
                + " list when a message uses it. On-device only, no web calls.");
        liveRow.sw.setChecked("1".equals(
            c.kv().get(com.replymate.core.live.LiveContext.KV_ENABLED, "1")));
        liveRow.sw.setOnCheckedChangeListener(
            new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    c.kv().put(com.replymate.core.live.LiveContext.KV_ENABLED, on ? "1" : "0");
                    Toast.makeText(SettingsActivity.this, on
                        ? "Live context on — replies can use today's real date & time"
                        : "Live context off — replies get no clock or word hints",
                        Toast.LENGTH_SHORT).show();
                }
            });
        root.addView(liveRow.row);
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

        TextView footer = Ui.sub(this, "\nReplyMate only READS notifications you allow. With the reply assistant,\na draft is sent only when YOU tap Approve — nothing auto-sends,\nand nothing syncs off this phone.");
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



    /** P-background-6 (repaired P-background-10): drive Android's proper approval
     *  flow for background generation — every missing approval is explained in
     *  plain words WITH the exact tap the user must make, the in-app approval
     *  (show-notifications) is requested here, and the button opens the FOCUSED
     *  system approval for the first screen-backed need (battery now opens the
     *  per-app "always run in background?" dialog, never the generic all-apps
     *  list; a maker battery screen is offered as an extra button only when it
     *  really resolves on this device). Switch = master intent (kv); the
     *  subtitle carries the real capability. */
    private void runAssistantApprovalFlow() {
        java.util.EnumSet<com.replymate.app.assistant.AssistantPrereq.Need> missing =
            com.replymate.app.assistant.AssistantPrereq.missing(this);
        if (missing.isEmpty()) return;
        if (missing.contains(com.replymate.app.assistant.AssistantPrereq.Need.POST_NOTIFICATIONS)
                && android.os.Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, REQ_POST_NOTIF);
        }
        boolean oemAvailable = missing.contains(
            com.replymate.app.assistant.AssistantPrereq.Need.BATTERY)
            && com.replymate.app.assistant.AssistantPrereq.oemBackgroundIntent(this) != null;
        StringBuilder msg = new StringBuilder(
            "Background generation needs these Android approvals:\n\n");
        com.replymate.app.assistant.AssistantPrereq.Need firstScreen = null;
        for (com.replymate.app.assistant.AssistantPrereq.Need n : missing) {
            msg.append("• ").append(com.replymate.app.assistant.AssistantPrereq.explain(n)).append('\n');
            if (firstScreen == null
                    && com.replymate.app.assistant.AssistantPrereq.screenIntent(this, n) != null) {
                firstScreen = n;
            }
        }
        String how = com.replymate.app.assistant.AssistantPrereq.howTo(
            firstScreen == null
                ? com.replymate.app.assistant.AssistantPrereq.Need.POST_NOTIFICATIONS
                : firstScreen);
        if (!how.isEmpty()) msg.append('\n').append(how).append('\n');
        if (oemAvailable) {
            msg.append("\nThis maker adds a SECOND background switch in its own battery"
                + " manager — \"Maker battery settings\" opens it; turn it on there too"
                + " if background delivery still stops.");
        }
        msg.append("\nReplyMate stays read-only and local-first —"
            + " nothing is ever sent without your Approve.");
        if (firstScreen == null) return;   // only the in-app request was missing
        final com.replymate.app.assistant.AssistantPrereq.Need fixNeed = firstScreen;
        final String label = com.replymate.app.assistant.AssistantPrereq.chip(firstScreen);
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this)
            .setTitle("Allow background generation")
            .setMessage(msg.toString())
            .setPositiveButton(
                fixNeed == com.replymate.app.assistant.AssistantPrereq.Need.BATTERY
                    ? "Allow in background" : ("Open " + label + " settings"),
                new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        if (fixNeed == com.replymate.app.assistant.AssistantPrereq.Need.BATTERY) {
                            String opened = com.replymate.app.assistant.AssistantPrereq
                                .openBatteryApprovalFlow(SettingsActivity.this);
                            Toast.makeText(SettingsActivity.this,
                                opened.isEmpty()
                                    ? "No battery screen found — open ReplyMate's app info"
                                        + " and set Battery → Unrestricted by hand."
                                    : "Opened: " + opened,
                                Toast.LENGTH_LONG).show();
                        } else {
                            com.replymate.app.assistant.AssistantPrereq.launch(
                                SettingsActivity.this,
                                com.replymate.app.assistant.AssistantPrereq
                                    .screenIntent(SettingsActivity.this, fixNeed));
                        }
                    }
                })
            .setNegativeButton("Later", null);
        if (oemAvailable) {
            b.setNeutralButton("Maker battery settings",
                new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        com.replymate.app.assistant.AssistantPrereq.launch(
                            SettingsActivity.this,
                            com.replymate.app.assistant.AssistantPrereq
                                .oemBackgroundIntent(SettingsActivity.this));
                    }
                });
        }
        b.show();
    }

    private void refreshRows() {
        if (c == null) return;
        com.replymate.core.model.ProviderDef active = c.providers().active();
        Ui.setRowSub(providerRow, active != null
            ? providerSummary(active)
            : "not configured — tap to add a provider");
        Ui.setRowSub(listenRow, ListenerStatus.isServiceEnabled(this)
            ? "enabled ✓ (read-only)" : "off — tap to open system settings");
        Ui.setRowSub(notifRow, ListenerStatus.canPostNotifications(this)
            ? "allowed ✓ — ReplyMate can alert you about new messages"
            : "blocked — tap to allow (alerts stay silent otherwise)");
        if (assistantRow != null) {
            // P-background-6: switch = the master intent (real kv state); subtitle =
            // the REAL capability — any missing Android approval is named, and the
            // row is tappable to fix it. Never a fake "on".
            assistantRow.sw.setEnabled(true);
            assistantRow.sw.setChecked(
                com.replymate.app.assistant.AssistantRunner.enabled(c));
            if (!com.replymate.app.assistant.AssistantRunner.enabled(c)) {
                Ui.setRowSub(assistantRow.row,
                    "off — new pings only alert, nothing is drafted");
            } else {
                java.util.EnumSet<com.replymate.app.assistant.AssistantPrereq.Need> missing =
                    com.replymate.app.assistant.AssistantPrereq.missing(this);
                if (missing.isEmpty()) {
                    Ui.setRowSub(assistantRow.row,
                        "on ✓ — access ✓ notifications ✓ battery ✓ · you approve every send");
                } else {
                    StringBuilder chips = new StringBuilder();
                    for (com.replymate.app.assistant.AssistantPrereq.Need n : missing) {
                        if (chips.length() > 0) chips.append(" · ");
                        chips.append(com.replymate.app.assistant.AssistantPrereq.chip(n));
                    }
                    Ui.setRowSub(assistantRow.row,
                        "on — blocked by: " + chips + " (tap to fix)");
                }
            }
        }
        com.replymate.core.auth.AuthSession s = c.sessions().get();
        Ui.setRowSub(accountRow, s == null
            ? "not signed in — Google / email code / guest"
            : s.isGuest()
                ? "guest session (local-only) — tap to sign in"
                : s.email + " · " + (s.provider.isEmpty() ? "email" : s.provider));
    }

    /** Active provider line for the Settings hub (friendly diagnostics, no secrets). */
    private String providerSummary(com.replymate.core.model.ProviderDef d) {
        String label = d.label.isEmpty() ? d.type.label : d.label;
        if (d.type.needsKey && (d.keyRef.isEmpty() || !c.vault().hasSecret(d.keyRef))) {
            return label + " — key missing, taps to fix";
        }
        if (d.modelName.isEmpty()) return label + " · no model selected yet";
        return "active: " + label + " · " + d.modelName + " ✓";
    }

    private String buildDiagnostics() {
        StringBuilder sb = new StringBuilder();
        DbHealth.Report r = DbHealth.check(c.db());
        sb.append("Database:\n").append(r.details()).append('\n');
        sb.append("Listener:\n");
        sb.append("  enabled in system: ").append(ListenerStatus.isServiceEnabled(this)).append('\n');
        sb.append("  own notifications: ").append(ListenerStatus.canPostNotifications(this)).append('\n');
        sb.append("  battery optimization ignored: ").append(ListenerStatus.batteryWhitelisted(this))
          .append("  (false + delivery trouble = set Battery → Unrestricted)").append('\n');
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
        // P-background-2: structured assistant ledger — every assistant failure with
        // conversation / provider / model / alert id / stage / reason / action / fix.
        sb.append("\nAssistant ledger (newest first):\n");
        sb.append("  assistant switch: ")
          .append(com.replymate.app.assistant.AssistantRunner.enabled(c) ? "ON" : "OFF")
          .append('\n');
        List<String> aLines = com.replymate.app.assistant.AssistantDiag.lines(c.kv(), 8);
        if (aLines.isEmpty()) {
            sb.append("  (no assistant events recorded)");
        } else {
            for (String l : aLines) {
                sb.append("  ").append(l).append('\n');
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
        // Provider audit: the most recent AI provider failure, verbatim — provider,
        // endpoint, model, HTTP status, the provider's own words, ReplyMate's read,
        // and the suggested fix. Written by every failing surface (see LastProviderError).
        String lastErr = c.kv().get(LastProviderError.KV_KEY, "");
        out.append("Last AI provider error:\n");
        out.append(lastErr.isEmpty() ? "  (none recorded)" : lastErr).append('\n');
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
