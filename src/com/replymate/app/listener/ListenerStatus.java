package com.replymate.app.listener;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.provider.Settings;

/** Read-only status of our system permissions (listener access + our own POST permission). */
public final class ListenerStatus {

    private ListenerStatus() { }

    /** Is ReplyMate enabled in system Notification Access? */
    public static boolean isServiceEnabled(Context ctx) {
        ComponentName cn = new ComponentName(ctx, RmNotificationListener.class);
        String flat = Settings.Secure.getString(ctx.getContentResolver(),
            "enabled_notification_listeners");
        if (flat == null || flat.isEmpty()) return false;
        return flat.contains(cn.flattenToString())
            || flat.contains(cn.flattenToShortString())
            || flat.contains(ctx.getPackageName() + "/" + RmNotificationListener.class.getName());
    }

    /** May we POST our own notifications? (33+ needs runtime grant; below it's implicit.) */
    public static boolean canPostNotifications(Context ctx) {
        if (Build.VERSION.SDK_INT < 33) return true;
        return ctx.checkSelfPermission("android.permission.POST_NOTIFICATIONS")
            == PackageManager.PERMISSION_GRANTED;
    }

    /** Deep-link to the system toggle screen for our listener. */
    public static void openAccessSettings(Context ctx) {
        Intent i = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ctx.startActivity(i);
    }
}
