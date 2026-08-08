package com.replymate.app.assistant;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import com.replymate.app.listener.ListenerStatus;
import java.util.EnumSet;

/** P-background-6: the REAL prerequisite check behind the Settings toggle for
 *  background generation — which Android approvals are missing, what each one is
 *  FOR, and the exact system screen that grants it. A switch alone can never
 *  grant these; the toggle drives Android's proper approval flow through this. */
public final class AssistantPrereq {

    public enum Need { NOTIFICATION_ACCESS, POST_NOTIFICATIONS, BATTERY }

    private AssistantPrereq() { }

    /** The approvals Background generation still lacks on this device. */
    public static EnumSet<Need> missing(Context ctx) {
        EnumSet<Need> out = EnumSet.noneOf(Need.class);
        if (!ListenerStatus.isServiceEnabled(ctx)) out.add(Need.NOTIFICATION_ACCESS);
        if (!ListenerStatus.canPostNotifications(ctx)) out.add(Need.POST_NOTIFICATIONS);
        if (!ListenerStatus.batteryWhitelisted(ctx)) out.add(Need.BATTERY);
        return out;
    }

    /** One user-readable sentence: what this approval is FOR. */
    public static String explain(Need n) {
        switch (n) {
            case NOTIFICATION_ACCESS:
                return "Notification access — lets ReplyMate READ new messages from the"
                    + " watched apps (strictly read-only; nothing is sent anywhere).";
            case POST_NOTIFICATIONS:
                return "Show notifications — lets ReplyMate show the ready-to-approve"
                    + " reply alert on top of other apps.";
            default:
                return "Battery: Unrestricted — stops Android freezing ReplyMate, so"
                    + " background generation keeps running instead of dying silently.";
        }
    }

    /** Short chip for the Settings subtitle, e.g. "battery". */
    public static String chip(Need n) {
        switch (n) {
            case NOTIFICATION_ACCESS: return "notification access";
            case POST_NOTIFICATIONS: return "show-notifications";
            default: return "battery";
        }
    }

    /** The exact system screen that grants this approval; null when there is no
     *  screen (POST_NOTIFICATIONS is an in-app runtime permission request). */
    public static Intent screenIntent(Need n) {
        switch (n) {
            case NOTIFICATION_ACCESS:
                return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            case BATTERY:
                return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            default:
                return null;
        }
    }
}
