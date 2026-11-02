package com.replymate.app;

import android.app.Application;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import com.replymate.app.di.AppContainer;

/** Application entry point. Builds the AppContainer once per process. */
public final class ReplyMateApp extends Application {

    private AppContainer container;

    @Override public void onCreate() {
        super.onCreate();
        try {
            container = new AppContainer(this);
            try { com.replymate.app.listener.NotifierPings.ensureChannels(this); }
            catch (RuntimeException ignored) { }
            container.logger().i("App", "ReplyMate process started, container ready");
        } catch (RuntimeException e) {
            // Container failure is fatal-but-diagnosable: shell screens must still open.
            android.util.Log.e("RM-App", "container init failed", e);
        }
        warmListenerStateOffMain();
        nudgeListenerRebind();
        sweepOnConnectivityReturn();
    }

    public AppContainer container() { return container; }

    /** P-anr-startup: on a cold install the FIRST kv read opens SQLite and pays
     *  the journal_mode=WAL commit fsync (device-proven: reason=6 "executing
     *  service" ANR with the main thread parked in fdatasync via
     *  SQLiteConnection.setJournalMode). That cost is deliberately paid HERE —
     *  on the single-threaded capture lane — so it provably never runs on the
     *  main thread during process start or any listener callback. The same
     *  task seeds the in-process rebind gate from the persisted stamp, then
     *  every later listener-path decision is I/O-free (RebindGate). */
    private void warmListenerStateOffMain() {
        final AppContainer c = container;
        com.replymate.app.platform.Tasks.ingest(new Runnable() {
            @Override public void run() {
                try {
                    com.replymate.app.listener.ListenerRebind.warmFromKv(
                        c == null ? null : c.kv());
                } catch (Throwable ignored) {
                    // warm/seed is opportunistic: an unreadable store leaves the
                    // gate fail-open, which is the RebindPolicy contract anyway
                }
            }
        });
    }

    public static AppContainer containerOf(android.content.Context ctx) {
        ReplyMateApp app = (ReplyMateApp) ctx.getApplicationContext();
        return app.container();
    }

    /** P-background-9: the official rebind nudge (API 24+). OEM builds are known
     *  to leave a granted NotificationListenerService unbound after process
     *  churn (MIUI/Transsion/ColorOS/etc.) until the toggle is flipped manually;
     *  every process start asks the system to rebind us. No-op when already
     *  bound or when access was never granted.
     *  P-listener-foundation §4: routed through the SINGLE bounded mechanism
     *  (ListenerRebind → RebindPolicy's shared 30s stamp) so process-start
     *  self-heal and disconnect recovery can never compete or storm. */
    private void nudgeListenerRebind() {
        com.replymate.app.listener.ListenerRebind.request(container, this, "process-start");
    }

    /** P-background-9: generations that failed while the network was down
     *  (airplane mode, deep Doze, dead zone) must not wait for the next message
     *  or an app open. While the process lives, the system's default-network
     *  callback tells us the moment connectivity returns; the same idempotent
     *  catch-up sweep then re-drives only what is still owed (drafts for
     *  unanswered latest incoming messages, re-alerts for waiting ones).
     *  Heavily throttled — per-network flaps, not per-packet. */
    private void sweepOnConnectivityReturn() {
        try {
            if (Build.VERSION.SDK_INT < 24) return;
            ConnectivityManager cm = (ConnectivityManager)
                getSystemService(CONNECTIVITY_SERVICE);
            if (cm == null) return;
            cm.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) {
                    final AppContainer c = container;
                    if (c == null) return;
                    com.replymate.app.assistant.AssistantRunner.retryUnansweredThrottled(
                        c, com.replymate.app.assistant.AssistantRunner
                            .NETWORK_SWEEP_MIN_INTERVAL_MS);
                }
            });
        } catch (Throwable ignored) {
            // connectivity recovery is opportunistic — the listener-connect sweep
            // remains as the always-available recovery path
        }
    }
}
