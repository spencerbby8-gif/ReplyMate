package com.replymate.app;

import android.app.Application;
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
    }

    public AppContainer container() { return container; }

    public static AppContainer containerOf(android.content.Context ctx) {
        ReplyMateApp app = (ReplyMateApp) ctx.getApplicationContext();
        return app.container();
    }
}
