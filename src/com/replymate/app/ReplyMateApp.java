package com.replymate.app;

import android.app.Application;
import android.content.ComponentName;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Build;
import android.service.notification.NotificationListenerService;
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
        sweepOnConnectivityReturn();
    }

    public AppContainer container() { return container; }

    public static AppContainer containerOf(android.content.Context ctx) {
        ReplyMateApp app = (ReplyMateApp) ctx.getApplicationContext();
        return app.container();
    }

    /** P-background-9: the official rebind nudge (API 24+). OEM builds are known
     *  to leave a granted NotificationListenerService unbound after process
     *  churn (MIUI/Transsion/etc.) until the toggle is flipped manually; every
     *  process start asks the system to rebind us. No-op when already bound or
     *  when access was never granted. Guarded: a broken OEM framework must never
     *  take the app down with it. */
    private void nudgeListenerRebind() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                NotificationListenerService.requestRebind(
                    new ComponentName(this,
                        com.replymate.app.listener.RmNotificationListener.class));
            }
        } catch (Throwable ignored) {
            // requestRebind is best-effort self-heal — never fatal
        }
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
