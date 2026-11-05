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

/** S13 — Settings hub. P21-production §5: grouped information architecture —
 *  ACCOUNT & PRIVACY · AI PROVIDERS · NOTIFICATION SOURCES · ASSISTANT BEHAVIOUR ·
 *  DATA & DIAGNOSTICS · ABOUT. Every pre-existing row/function is preserved
 *  1:1 (re-grouped, never duplicated or dropped). No "Appearance" group exists:
 *  the app has zero appearance settings and an empty section would be a lie. */
public final class SettingsActivity extends Activity {

    private static final int REQ_POST_NOTIF = 42;

    private AppContainer c;
    private LinearLayout root;
    private LinearLayout providerRow, listenRow, notifRow, accountRow, depthRowRef;
    private Ui.ToggleRow assistantRow;
    /** Guards the switch's listener while we programmatically revert it (P-intel-14). */
    private boolean suppressAssistantToggle;

    /** P-intelligence-19R: the owner asked to enable while approvals were missing
     *  and left for a system screen — onResume re-evaluates the LIVE state and
     *  finishes the enable only when nothing can still block execution. */
    private static final String KV_PENDING_ENABLE = "assistant.pending_enable";

    /** Group header (uppercase grey label) separating one IA category. */
    private void addSection(String title) {
        TextView l = Ui.label(this, title);
        l.setPadding(0, Ui.dp(this, 18), 0, 0);
        root.addView(l);
    }

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

        addSection("ACCOUNT & PRIVACY");
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


        addSection("AI PROVIDERS");
        providerRow = Ui.row(this, "AI providers", "");
        providerRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, ProviderActivity.class));
            }
        });
        root.addView(providerRow);
        root.addView(Ui.divider(this));

        // P21-review §7-audit: cross-provider fallback is an owner CONSENT
        // decision — a backup provider receives the draft content with ITS OWN
        // key, billing and privacy mode. Default ON (the §7 defect was "no
        // fallback"); every hop is audited and the saved draft names the model
        // that actually answered. The built-in shared key can never be a
        // fallback target while the owner runs their own key.
        Ui.ToggleRow fallbackRow = Ui.toggleRow(this, "Cross-provider fallback",
            "when the active provider fails a draft, each other provider YOU"
            + " configured gets one try (max 2) — its own key, billing and"
            + " privacy rules apply, and nothing extra is sent while off");
        fallbackRow.sw.setChecked(
            com.replymate.core.privacy.FallbackPolicy.enabled(c.kv()));
        fallbackRow.sw.setOnCheckedChangeListener(
            new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(
                        android.widget.CompoundButton b, boolean on) {
                    c.kv().put(com.replymate.core.privacy.FallbackPolicy.KV_ENABLED,
                        on ? "1" : "0");
                    Ui.setRowSub(fallbackRow.row, on
                        ? "on — a failing provider gets up to 2 backup tries on your other providers"
                        : "off — the original error stands; no other provider is contacted");
                }
            });
        root.addView(fallbackRow.row);
        root.addView(Ui.divider(this));

        addSection("NOTIFICATION SOURCES");
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

        addSection("ASSISTANT BEHAVIOUR");
        // P-background: master switch for the assistant. Real switch, live state,
        // honest subtitle (permission prerequisite + human-approval contract).
        assistantRow = Ui.toggleRow(this, "Background reply assistant",
            "drafts a reply when a watched chat pings — you approve every send");
        assistantRow.sw.setChecked(
            com.replymate.app.assistant.AssistantRunner.enabled(c));
        assistantRow.sw.setOnCheckedChangeListener(
            new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    if (suppressAssistantToggle) return;
                    if (!on) {
                        c.kv().put(
                            com.replymate.app.assistant.AssistantRunner.KV_ENABLED, "0");
                        refreshRows();
                        return;
                    }
                    // P-intelligence-14 (re-substantiated P-intelligence-19R): the switch
                    // may only READ ON when Android can genuinely run us in the
                    // background — never a claimed "on" while ANY lever can still
                    // block execution (listener, alerts, allowlist, Android 9+
                    // restricted bucket, the standby RESTRICTED bucket, and the
                    // unverifiable ColorOS "Allow background activity" switch until
                    // the owner confirms it). Blocked ON: persist OFF + a pending
                    // flag, revert the switch, and walk the approval flow; onResume
                    // finishes the enable once the last lever is granted.
                    com.replymate.core.assistant.BackgroundReadiness.Verdict ready =
                        com.replymate.app.assistant.AssistantPrereq.readiness(
                            SettingsActivity.this, c.kv());
                    if (ready.mayClaimReady()) {
                        c.kv().put(
                            com.replymate.app.assistant.AssistantRunner.KV_ENABLED, "1");
                        c.kv().put(KV_PENDING_ENABLE, "0");
                        toastReady(ready);
                    } else {
                        c.kv().put(
                            com.replymate.app.assistant.AssistantRunner.KV_ENABLED, "0");
                        c.kv().put(KV_PENDING_ENABLE, "1");
                        suppressAssistantToggle = true;
                        assistantRow.sw.setChecked(false);
                        suppressAssistantToggle = false;
                        runAssistantApprovalFlow();
                    }
                    refreshRows();
                }
            });
        assistantRow.row.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                // blocked-state re-entry: tap the row itself to re-run the flow
                if (com.replymate.app.assistant.AssistantRunner.enabled(c)
                        && !com.replymate.app.assistant.AssistantPrereq
                            .missing(SettingsActivity.this, c.kv()).isEmpty()) {
                    runAssistantApprovalFlow();
                }
            }
        });
        root.addView(assistantRow.row);
        root.addView(Ui.divider(this));

        // Live context: device clock only (the dated glossary is gone — slang and
        // meanings are an automatic live-search capability now, not a toggle).
        Ui.ToggleRow liveRow = Ui.toggleRow(this, "Live context (date & time)",
            "adds this phone's real date/time/timezone to generation. Word, slang"
                + " and current-info questions are looked up automatically when"
                + " needed — that's automatic, and stays on this phone's rules.");
        liveRow.sw.setChecked("1".equals(
            c.kv().get(com.replymate.core.live.LiveContext.KV_ENABLED, "1")));
        liveRow.sw.setOnCheckedChangeListener(
            new android.widget.CompoundButton.OnCheckedChangeListener() {
                @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean on) {
                    c.kv().put(com.replymate.core.live.LiveContext.KV_ENABLED, on ? "1" : "0");
                    Toast.makeText(SettingsActivity.this, on
                        ? "Live context on — replies can use today's real date & time"
                        : "Live context off — replies get no device-clock line",
                        Toast.LENGTH_SHORT).show();
                }
            });
        root.addView(liveRow.row);
        root.addView(Ui.divider(this));

        // P-intelligence-5 (planning depth): how much LOCAL planning rides before
        // the model writes. Zero extra provider calls at every level.
        depthRowRef = Ui.row(this, "Planning depth", "");
        updateDepthSub();
        depthRowRef.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickDepth(); }
        });
        root.addView(depthRowRef);
        root.addView(Ui.divider(this));

        LinearLayout voiceRow = Ui.row(this, "My voice (global style)",
            "tone, length, emoji, humor… the base style every assistant reply is built from");
        voiceRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, VoiceActivity.class));
            }
        });
        root.addView(voiceRow);
        root.addView(Ui.divider(this));

        addSection("DATA & DIAGNOSTICS");
        LinearLayout usageRow = Ui.row(this, "Usage dashboard",
            "local metering of every AI call (count + tokens)");
        usageRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, UsageActivity.class));
            }
        });
        root.addView(usageRow);
        root.addView(Ui.divider(this));

        // P21-production §6: Diagnostics is a REAL dedicated screen (live report,
        // copy-for-sharing with redaction) — no more inline accordion.
        LinearLayout diagRow = Ui.row(this, "Diagnostics",
            "dedicated screen — database, listener, per-app stats, alert verdicts; safe-to-share copy");
        root.addView(diagRow);
        diagRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, DiagnosticsActivity.class));
            }
        });
        root.addView(Ui.divider(this));

        LinearLayout auditRow = Ui.row(this, "Prompt audit (per contact, read-only)",
            "the exact text sent to your provider for any draft, stored on this phone");
        auditRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickPromptAuditContact(); }
        });
        root.addView(auditRow);
        root.addView(Ui.divider(this));

        addSection("ABOUT");
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
        // P-intelligence-19R: RECHECK the live state after returning from a system
        // screen the approval flow opened. If the owner just granted the last
        // missing lever, finish the pending enable here — and only here: "Ready"
        // is claimed from a FRESH verdict, never from a pre-screen snapshot.
        if (c != null && "1".equals(c.kv().get(KV_PENDING_ENABLE, "0"))) {
            com.replymate.core.assistant.BackgroundReadiness.Verdict v =
                com.replymate.app.assistant.AssistantPrereq.readiness(this, c.kv());
            if (v.mayClaimReady()) {
                c.kv().put(com.replymate.app.assistant.AssistantRunner.KV_ENABLED, "1");
                c.kv().put(KV_PENDING_ENABLE, "0");
                toastReady(v);
                // enabling flips the listener-era catch-up on too: anything queued
                // while the levers were missing gets re-driven now
                com.replymate.app.assistant.AssistantRunner.retryUnanswered(c);
            }
        }
        refreshRows();
    }

    /** Comma list of the verdict's blockers as owner-visible chips. */
    private static String chipsOf(com.replymate.core.assistant.BackgroundReadiness.Verdict v) {
        StringBuilder sb = new StringBuilder();
        for (com.replymate.core.assistant.BackgroundReadiness.Lever l : v.missing) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(com.replymate.app.assistant.AssistantPrereq.chip(mapLever(l)));
        }
        return sb.toString();
    }

    private static com.replymate.app.assistant.AssistantPrereq.Need mapLever(
            com.replymate.core.assistant.BackgroundReadiness.Lever l) {
        switch (l) {
            case NOTIFICATION_ACCESS:
                return com.replymate.app.assistant.AssistantPrereq.Need.NOTIFICATION_ACCESS;
            case POST_NOTIFICATIONS:
                return com.replymate.app.assistant.AssistantPrereq.Need.POST_NOTIFICATIONS;
            case BACKGROUND_RESTRICTED:
                return com.replymate.app.assistant.AssistantPrereq.Need.BACKGROUND_RESTRICTED;
            case STANDBY_RESTRICTED:
                return com.replymate.app.assistant.AssistantPrereq.Need.STANDBY_RESTRICTED;
            case OPLUS_BACKGROUND:
                return com.replymate.app.assistant.AssistantPrereq.Need.OPLUS_BACKGROUND;
            default:
                return com.replymate.app.assistant.AssistantPrereq.Need.BATTERY;
        }
    }

    /** Ready toast — warnings are ALWAYS named, never hidden behind a check mark. */
    private void toastReady(com.replymate.core.assistant.BackgroundReadiness.Verdict v) {
        String warn = v.warnings.contains(
            com.replymate.core.assistant.BackgroundReadiness.Lever.POWER_SAVE_MODE)
            ? " (power save mode is ON — drafts may pause until it ends)" : "";
        Toast.makeText(this, "Background replies on — Ready \u2713" + warn,
            Toast.LENGTH_LONG).show();
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                                     int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_POST_NOTIF) {
            refreshRows();
            // P-intelligence-7: catch up messages swallowed while alerts were denied.
            if (grantResults.length > 0
                    && grantResults[0]
                        == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                com.replymate.app.assistant.AssistantRunner.retryUnanswered(c);
            }
        }
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
            com.replymate.app.assistant.AssistantPrereq.missing(this, c.kv());
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
        final boolean oplus = com.replymate.app.assistant.AssistantPrereq.isOplus();
        if (oemAvailable) {
            msg.append(oplus
                ? "\nThis phone (ColorOS) keeps a SECOND background switch \u2014 \"Allow"
                    + " background activity\" \u2014 on ReplyMate's Battery usage page."
                    + " The button below opens exactly that page: switch it ON and"
                    + " confirm Allow. (No app can read that switch's state, so"
                    + " ReplyMate never claims it \u2014 the row re-checks the readable"
                    + " levers every time this screen opens.)"
                : "\nThis maker adds a SECOND background switch in its own battery"
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
                    ? "Allow in background"
                    : fixNeed == com.replymate.app.assistant.AssistantPrereq.Need
                            .BACKGROUND_RESTRICTED
                        ? "Open app battery settings"
                        : fixNeed == com.replymate.app.assistant.AssistantPrereq.Need
                                .OPLUS_BACKGROUND
                            ? "Open Oppo battery usage page"
                            : fixNeed == com.replymate.app.assistant.AssistantPrereq.Need
                                    .STANDBY_RESTRICTED
                                ? "Open ReplyMate app info"
                                : ("Open " + label + " settings"),
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
                            // P-19R: guarded launch — an unresolvable page falls
                            // back to app-info instead of dying silently
                            android.content.Intent target =
                                com.replymate.app.assistant.AssistantPrereq
                                    .screenIntent(SettingsActivity.this, fixNeed);
                            if (!com.replymate.app.assistant.AssistantPrereq.launch(
                                    SettingsActivity.this, target)) {
                                com.replymate.app.assistant.AssistantPrereq.launch(
                                    SettingsActivity.this,
                                    com.replymate.app.assistant.AssistantPrereq
                                        .appDetailsIntent(SettingsActivity.this));
                            }
                        }
                    }
                })
            .setNegativeButton("Later", null);
        if (oemAvailable) {
            b.setNeutralButton(oplus ? "Oppo battery usage page" : "Maker battery settings",
                new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        com.replymate.app.assistant.AssistantPrereq.launch(
                            SettingsActivity.this,
                            com.replymate.app.assistant.AssistantPrereq
                                .oemBackgroundIntent(SettingsActivity.this));
                    }
                });
        } else if (missing.contains(
                com.replymate.app.assistant.AssistantPrereq.Need.OPLUS_BACKGROUND)) {
            // P-intelligence-19R: Android cannot READ ColorOS's "Allow background
            // activity" switch (no public API — verified against the platform
            // docs), so the flow closes with the owner's explicit confirmation.
            // The confirmation never fakes a probe: it is recorded AS "the owner
            // told us", and setting it when the BATTERY allowlist is later lost
            // re-arms the whole flow.
            b.setNeutralButton("I've enabled it \u2713",
                new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        com.replymate.app.assistant.AssistantPrereq
                            .setOplusConfirmed(c.kv(), true);
                        com.replymate.core.assistant.BackgroundReadiness.Verdict now =
                            com.replymate.app.assistant.AssistantPrereq
                                .readiness(SettingsActivity.this, c.kv());
                        if (now.mayClaimReady()
                                && "1".equals(c.kv().get(KV_PENDING_ENABLE, "0"))) {
                            c.kv().put(
                                com.replymate.app.assistant.AssistantRunner.KV_ENABLED, "1");
                            c.kv().put(KV_PENDING_ENABLE, "0");
                            toastReady(now);
                            com.replymate.app.assistant.AssistantRunner
                                .retryUnanswered(c);
                        } else {
                            Toast.makeText(SettingsActivity.this,
                                "Noted — still missing: " + chipsOf(now),
                                Toast.LENGTH_LONG).show();
                            runAssistantApprovalFlow();
                        }
                        refreshRows();
                    }
                });
        }
        b.show();
    }

    /** P-intelligence-5: the 3-way planning depth picker (shared dialog pattern —
     *  one tap, single choice, immediate save, subtitle mirrors real state). */
    private void pickDepth() {
        final String[] keys = {
            com.replymate.core.plan.PlanDepth.BASIC,
            com.replymate.core.plan.PlanDepth.NORMAL,
            com.replymate.core.plan.PlanDepth.DEEP };
        final String[] titles = { "Basic", "Normal", "Deep" };
        final String[] blurbs = {
            "no planning block — the classic prompt, fastest",
            "one compact plan line: what the moment needs + reply length (default)",
            "full plan: situation, topic, which burst lines count, what to skip,"
                + " and length fit — same single provider call" };
        String cur = com.replymate.core.plan.PlanDepth.normalize(
            c.kv().get(com.replymate.core.plan.PlanDepth.KV_KEY,
                com.replymate.core.plan.PlanDepth.NORMAL));
        int checked = com.replymate.core.plan.PlanDepth.DEEP.equals(cur) ? 2
            : com.replymate.core.plan.PlanDepth.BASIC.equals(cur) ? 0 : 1;
        CharSequence[] items = new CharSequence[3];
        for (int i = 0; i < 3; i++) items[i] = titles[i] + " — " + blurbs[i];
        new android.app.AlertDialog.Builder(this)
            .setTitle("Planning depth")
            .setSingleChoiceItems(items, checked,
                new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int which) {
                        c.kv().put(com.replymate.core.plan.PlanDepth.KV_KEY, keys[which]);
                        updateDepthSub();
                        d.dismiss();
                    }
                })
            .setNegativeButton("Close", null)
            .show();
    }

    private void updateDepthSub() {
        if (depthRowRef == null || c == null) return;
        String cur = com.replymate.core.plan.PlanDepth.normalize(
            c.kv().get(com.replymate.core.plan.PlanDepth.KV_KEY,
                com.replymate.core.plan.PlanDepth.NORMAL));
        Ui.setRowSub(depthRowRef,
            com.replymate.core.plan.PlanDepth.DEEP.equals(cur)
                ? "Deep — full planning block before the reply (still one AI call)"
                : com.replymate.core.plan.PlanDepth.BASIC.equals(cur)
                    ? "Basic — no planning block (classic prompt)"
                    : "Normal — compact plan line before the reply (still one AI call)");
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
            suppressAssistantToggle = true;
            assistantRow.sw.setChecked(
                com.replymate.app.assistant.AssistantRunner.enabled(c));
            suppressAssistantToggle = false;
            if (!com.replymate.app.assistant.AssistantRunner.enabled(c)) {
                Ui.setRowSub(assistantRow.row,
                    "off — new pings only alert, nothing is drafted");
            } else {
                // P-intelligence-19R: the subtitle is a FRESH readiness verdict,
                // never a persisted claim — allowlist-only previously lied here.
                com.replymate.core.assistant.BackgroundReadiness.Verdict v =
                    com.replymate.app.assistant.AssistantPrereq.readiness(this, c.kv());
                if (v.mayClaimReady()) {
                    Ui.setRowSub(assistantRow.row,
                        "Ready ✓ — access ✓ notifications ✓ battery ✓ · you approve every send"
                            + (v.warnings.contains(com.replymate.core.assistant
                                    .BackgroundReadiness.Lever.POWER_SAVE_MODE)
                                ? " · power save ON — drafts may pause until it ends" : ""));
                } else {
                    // only reachable from an older build's persisted ON or a REVOKED
                    // grant — label it degraded, never a clean \"on\".
                    StringBuilder chips = new StringBuilder();
                    for (com.replymate.core.assistant.BackgroundReadiness.Lever l : v.missing) {
                        if (chips.length() > 0) chips.append(" · ");
                        chips.append(com.replymate.app.assistant.AssistantPrereq.chip(mapLever(l)));
                    }
                    Ui.setRowSub(assistantRow.row,
                        "limited — blocked by: " + chips + " (tap to fix)");
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

    /** P21-review: contact picker for the read-only prompt audit (drafts only —
     *  a contact without drafts has nothing to show). */
    private void pickPromptAuditContact() {
        java.util.List<com.replymate.core.model.Contact> all = c.contacts().all();
        final java.util.List<com.replymate.core.model.Contact> withDrafts =
            new java.util.ArrayList<com.replymate.core.model.Contact>();
        for (com.replymate.core.model.Contact ct : all) {
            if (!c.drafts().byContact(ct.id, 1).isEmpty()) withDrafts.add(ct);
        }
        if (withDrafts.isEmpty()) {
            Toast.makeText(this, "No drafts yet — a prompt audit appears after the first draft",
                Toast.LENGTH_LONG).show();
            return;
        }
        final String[] names = new String[withDrafts.size()];
        for (int i = 0; i < withDrafts.size(); i++) names[i] = withDrafts.get(i).displayName;
        new android.app.AlertDialog.Builder(this)
            .setTitle("Prompt audit — pick a contact")
            .setItems(names, new android.content.DialogInterface.OnClickListener() {
                @Override public void onClick(android.content.DialogInterface d, int which) {
                    android.content.Intent i = new android.content.Intent(
                        SettingsActivity.this, PromptAuditActivity.class);
                    i.putExtra(PromptAuditActivity.EXTRA_CONTACT_ID,
                        withDrafts.get(which).id);
                    startActivity(i);
                }
            })
            .setNegativeButton("Cancel", null)
            .show();
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


}
