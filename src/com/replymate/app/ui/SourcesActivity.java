package com.replymate.app.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
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

/** S-P3a — Notification sources: the catalog of messaging apps ReplyMate can read,
 *  discovered against what is actually installed, with a per-app on/off switch and
 *  live per-app counters. Disabled apps are dropped BEFORE any processing. */
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

        TextView header = Ui.tv(this, "‹ Notification sources", 22, Ui.PRIMARY);
        header.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        header.setPadding(0, 0, 0, Ui.dp(this, 8));
        header.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { finish(); }
        });
        root.addView(header);

        TextView explainer = Ui.sub(this,
            "ReplyMate reads notifications ONLY from the apps below and only while "
            + "listening access is granted in system settings. Apps you switch off are "
            + "ignored before any processing. New apps start off.\n\n"
            + "Support: FULL = per-sender history · PARTIAL = title/text based · "
            + "LIMITED = depends heavily on what the app publishes.");
        root.addView(explainer);
        root.addView(Ui.divider(this));

        boolean listening = com.replymate.app.listener.ListenerStatus.isServiceEnabled(this);
        LinearLayout accessRow = Ui.row(this, "Notification access",
            listening ? "granted ✓" : "off — replies stay manual until granted");
        accessRow.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                com.replymate.app.listener.ListenerStatus.openAccessSettings(SourcesActivity.this);
            }
        });
        root.addView(accessRow);
        root.addView(Ui.divider(this));

        renderApps();
    }

    @Override protected void onResume() {
        super.onResume();
        if (c != null && root != null && root.getChildCount() > 0) {
            // Re-render so toggles/stats reflect reality when returning.
            while (root.getChildCount() > 4) root.removeViewAt(4);
            renderApps();
        }
    }

    private void renderApps() {
        Set<Channel> enabled = ParserRegistry.enabledFromKv(c.kv(), DEFAULTS_ON);
        ListenerStats stats = new ListenerStats(c.kv());

        for (Channel ch : catalogOrder()) {
            List<String> pkgs = WatchedApps.packagesFor(ch);
            if (pkgs.isEmpty()) continue;                       // MANUAL: not a source app

            boolean installed = false;
            for (String p : pkgs) installed |= DeepLinks.packageExists(this, p);
            final boolean isOn = enabled.contains(ch);
            final Channel channel = ch;
            final String firstPkg = pkgs.get(0);
            final boolean wasInstalled = installed;

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
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView badge = Ui.tv(this, isOn ? "ON" : "OFF", 13,
                isOn ? Ui.GREEN : Ui.DIM);
            badge.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            badge.setPadding(Ui.dp(this, 8), 0, 0, 0);
            top.addView(badge);

            String tier = tierOf(ch);
            String state = (wasInstalled ? "installed ✓" : "not installed")
                + " · " + tier + (isOn ? "" : " · ignored");
            TextView stateTv = Ui.sub(this, state);
            stateTv.setPadding(0, Ui.dp(this, 2), 0, 0);
            card.addView(stateTv);

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

            card.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (!wasInstalled) {
                        // Offer the official store page instead of a dead toggle.
                        DeepLinks.openStorePage(SourcesActivity.this, firstPkg);
                        return;
                    }
                    String key = "watch." + channel.wire;
                    boolean cur = enabledNow().contains(channel);
                    c.kv().put(key, cur ? "0" : "1");
                    refresh();
                }
            });

            root.addView(card);
            root.addView(Ui.divider(this));
        }

        TextView foot = Ui.sub(this,
            "\nWhatsApp Business, Telegram forks and other re-packaged builds are "
            + "deliberately not read. ReplyMate never uses Accessibility, screen "
            + "scraping or unofficial APIs.");
        root.addView(foot);
    }

    private void refresh() {
        while (root.getChildCount() > 4) root.removeViewAt(4);
        renderApps();
    }

    private Set<Channel> enabledNow() {
        return ParserRegistry.enabledFromKv(c.kv(), DEFAULTS_ON);
    }

    /** Distinct source channels in catalog order (X's two packages collapse to one row). */
    private static List<Channel> catalogOrder() {
        LinkedHashSet<Channel> ordered = new LinkedHashSet<Channel>();
        for (WatchedApps.AppDef def : WatchedApps.all()) ordered.add(def.channel);
        return new ArrayList<Channel>(ordered);
    }

    /** Honest support tier per app, matching the parser actually used. */
    private static String tierOf(Channel ch) {
        switch (ch) {
            case WHATSAPP:
            case TELEGRAM:
            case SIGNAL:
            case GOOGLE_MESSAGES:
            case MESSENGER:
                return "FULL";
            case SLACK:
            case DISCORD:
                return "PARTIAL";
            case INSTAGRAM:
            case X:
            case TIKTOK:
            default:
                return "LIMITED";
        }
    }
}
