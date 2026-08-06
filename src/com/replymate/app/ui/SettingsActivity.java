package com.replymate.app.ui;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
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
                if (Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                            != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(
                        new String[] {"android.permission.POST_NOTIFICATIONS"}, REQ_POST_NOTIF);
                }
            }
        });
        root.addView(notifRow);
        root.addView(Ui.divider(this));

        LinearLayout diagRow = Ui.row(this, "Diagnostics", "database + listener self-test, recent events");
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
        sb.append("  last event: ").append(tsLine(IngestCoordinator.KV_LAST_EVENT)).append('\n');
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

    private String tsLine(String key) {
        long ts = parseLong(c.kv().get(key, ""));
        return ts > 0 ? TimeFmt.dayTime(ts) : "—";
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }
}
