package com.replymate.app.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.replymate.app.R;
import com.replymate.app.ReplyMateApp;
import com.replymate.app.di.AppContainer;
import com.replymate.app.platform.DeepLinks;
import com.replymate.core.listener.ListenerStats;
import com.replymate.core.listener.ParserRegistry;
import com.replymate.core.listener.WatchedApps;
import com.replymate.core.model.Channel;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** S-P3a — Notification sources (P3-repair): every status label on this screen is
 *  computed from REAL state — installed = PackageManager visibility query (needs
 *  the manifest <queries> entries on Android 11+), enabled = kv watch flags,
 *  counters = ListenerStats, tier = the parser actually wired in WatchedApps.
 *  Disabled apps are dropped BEFORE any processing; new apps start OFF. */
public final class SourcesActivity extends Activity {

    /** Same default-on set as the listener ({WA, TG}); every other app starts OFF. */
    private static final Set<Channel> DEFAULTS_ON =
        EnumSet.of(Channel.WHATSAPP, Channel.TELEGRAM);

    private AppContainer c;
    private LinearLayout root;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sources);
        c = ReplyMateApp.containerOf(this);
        root = (LinearLayout) findViewById(R.id.sources_root);
        render();
    }

    @Override protected void onResume() {
        super.onResume();
        if (c != null) render();        // toggles/stats/access state may have changed
    }

    private void render() {
        root.removeAllViews();

        TextView header = Ui.tv(this, "‹ Notification sources", 22, Ui.PRIMARY);
        header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.setPadding(0, 0, 0, Ui.dp(this, 8));
        header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(header);

        boolean listening = com.replymate.app.listener.ListenerStatus.isServiceEnabled(this);
        Set<Channel> enabled = ParserRegistry.enabledFromKv(c.kv(), DEFAULTS_ON);

        // --- real-state summary line -----------------------------------------
        int installedCount = 0;
        int watchingInstalled = 0;
        List<String> installedLabels = new ArrayList<String>();
        for (WatchedApps.AppDef def : WatchedApps.all()) {
            if (DeepLinks.packageExists(this, def.packageName)) {
                if (!installedLabels.contains(def.label)) installedLabels.add(def.label);
                installedCount++;
                if (enabled.contains(def.channel)) watchingInstalled++;
            }
        }
        TextView summary = Ui.sub(this,
            installedCount + " of " + WatchedApps.all().size() + " supported apps installed"
                + (listening
                    ? " · listening access granted ✓"
                    : " · listening access OFF — enable it below"));
        summary.setPadding(0, 0, 0, Ui.dp(this, 6));
        root.addView(summary);
        if (!listening || watchingInstalled == 0) {
            TextView state = Ui.tv(this,
                !listening
                    ? "Nothing is being read right now (access off in system settings)."
                    : "Listening access is on, but no installed app is enabled below.",
                13, Color.rgb(240, 180, 80)); // amber — a real state, worth surfacing
            state.setPadding(0, 0, 0, Ui.dp(this, 6));
            root.addView(state);
        }
        root.addView(Ui.divider(this));

        // --- system access row (real action) ----------------------------------
        LinearLayout accessRow = Ui.row(this, "Notification access",
            listening ? "granted ✓ — tap to review in system settings"
                      : "off — tap to open system settings");
        accessRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                com.replymate.app.listener.ListenerStatus.openAccessSettings(SourcesActivity.this);
            }
        });
        root.addView(accessRow);
        root.addView(Ui.divider(this));

        // --- per-app cards, all data-driven ------------------------------------
        ListenerStats stats = new ListenerStats(c.kv());
        for (Channel ch : catalogOrder()) {
            List<String> pkgs = WatchedApps.packagesFor(ch);
            if (pkgs.isEmpty()) continue;
            root.addView(appCard(ch, pkgs, enabled, stats));
            root.addView(Ui.divider(this));
        }

        TextView foot = Ui.sub(this,
            "\nSupport tiers describe the wired parser: FULL = per-sender history · "
            + "PARTIAL = title/text heuristics · LIMITED = depends heavily on what the "
            + "app publishes.\n\nWhatsApp Business, Telegram forks and other re-packaged "
            + "builds are deliberately not read. ReplyMate never uses Accessibility, "
            + "screen scraping or unofficial APIs.");
        root.addView(foot);
    }

    private View appCard(final Channel ch, List<String> pkgs, final Set<Channel> enabled,
                         ListenerStats stats) {
        boolean installed = false;
        for (String p : pkgs) installed |= DeepLinks.packageExists(this, p);
        final boolean isInstalled = installed;
        final boolean isOn = enabled.contains(ch);
        final String primaryPkg = pkgs.get(0);
        final Channel channel = ch;

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(android.view.Gravity.CENTER_VERTICAL);
        card.addView(top);

        TextView name = Ui.tv(this, WatchedApps.labelFor(ch), 17, Ui.PRIMARY);
        name.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        top.addView(name, new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        // Right badge shows the REAL effective state.
        String badgeText = !isInstalled ? "GET" : (isOn ? "ON" : "OFF");
        int badgeColor = !isInstalled ? Ui.ACCENT : (isOn ? Ui.GREEN : Ui.DIM);
        TextView badge = Ui.tv(this, badgeText, 13, badgeColor);
        badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        badge.setPadding(Ui.dp(this, 8), 0, 0, 0);
        top.addView(badge);

        // Status line: real detection + registry tier + effective monitoring state.
        WatchedApps.Tier tier = WatchedApps.tierFor(ch);
        String status = (isInstalled ? "installed ✓" : "not installed on this device")
            + " · " + (tier == null ? "?" : tier.name());
        if (isInstalled) status += isOn ? " · watching" : " · ignored (off)";
        TextView statusTv = Ui.sub(this, status);
        statusTv.setPadding(0, Ui.dp(this, 2), 0, 0);
        card.addView(statusTv);

        if (isInstalled) {
            TextView statsTv = Ui.sub(this,
                "received " + stats.receivedOf(ch)
                + " · parsed " + stats.parsedOf(ch)
                + " · ignored " + stats.ignoredOf(ch)
                + " · failed " + stats.failedOf(ch));
            statsTv.setTextSize(11);
            statsTv.setPadding(0, Ui.dp(this, 2), 0, 0);
            card.addView(statsTv);

            String reason = stats.lastReasonOf(ch);
            if (reason != null && !reason.isEmpty()) {
                TextView reasonTv = Ui.sub(this, "last: " + reason);
                reasonTv.setTextSize(11);
                reasonTv.setTextColor(Color.rgb(240, 180, 80)); // amber
                card.addView(reasonTv);
            }
        }

        card.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!isInstalled) {
                    // Real action for missing apps: official store page.
                    DeepLinks.openStorePage(SourcesActivity.this, primaryPkg);
                    return;
                }
                String key = "watch." + channel.wire;
                boolean cur = ParserRegistry.enabledFromKv(c.kv(), DEFAULTS_ON).contains(channel);
                c.kv().put(key, cur ? "0" : "1");
                render();
            }
        });
        return card;
    }

    /** Distinct source channels in catalog order (X's two packages collapse to one card). */
    private static List<Channel> catalogOrder() {
        LinkedHashSet<Channel> ordered = new LinkedHashSet<Channel>();
        for (WatchedApps.AppDef def : WatchedApps.all()) ordered.add(def.channel);
        return new ArrayList<Channel>(ordered);
    }
}
