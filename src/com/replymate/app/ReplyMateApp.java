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
        nudgeListenerRebind();
        warmDatabaseOffMain();
        sweepOnConnectivityReturn();
    }

    /** P-anr-startup: the first SQLite open (onConfigure → WAL switch → journal
     *  commit fsync) must never run on the main thread — emulator evidence
     *  (runs 41386158198/41404621261/41407382911, pulled procexitstore stack)
     *  showed exactly that chain inside the listener-service bind window
     *  recording "executing service …RmNotificationListener" ANR and later
     *  killing the process. The listener path no longer opens the DB on main
     *  (RebindGate decides from memory; DiagQueue writes async), and this
     *  background open takes the cold-start fsync cost off any user-facing
     *  first-read as well. A warmup failure is irrelevant here — real reads
     *  retry on their own lanes. */
    private void warmDatabaseOffMain() {
        final AppContainer c = container;
        if (c == null) return;
        com.replymate.app.platform.Tasks.bg(new Runnable() {
            @Override public void run() {
                try { c.kv().contains("db.warmup"); } catch (RuntimeException ignored) { }
            }
        });
    }

    public AppContainer container() { return container; }

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
