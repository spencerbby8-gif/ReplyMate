package com.replymate.app.assistant;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import com.replymate.app.listener.ListenerStatus;
import java.util.EnumSet;

/** P-background-6: the REAL prerequisite check behind the Settings toggle for
 *  background generation — which Android approvals are missing, what each one is
 *  FOR, and the exact system screen that grants it. A switch alone can never
 *  grant these; the toggle drives Android's proper approval flow through this.
 *
 *  P-background-10 repair (real-device failure): the battery fix previously
 *  opened ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS — the generic ALL-APPS
 *  battery list — and dumped the owner there to hunt for ReplyMate. The CORRECT
 *  system approval flow is ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS with a
 *  package: data URI (the focused "let this app always run in the background?"
 *  dialog), with the all-apps list and the app-details page as documented
 *  fallbacks for OEMs that strip the request dialog. A maker-specific battery
 *  screen is offered too — but ONLY when the package manager says the intent
 *  actually resolves on this device (unresolvable candidates never surface, so
 *  nothing is ever faked). Every launch is guarded: canLaunch + try/catch. */
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

    /** Exactly what the user must DO on the screen that opens. */
    public static String howTo(Need n) {
        switch (n) {
            case NOTIFICATION_ACCESS:
                return "On the screen that opens, find ReplyMate and switch its access ON.";
            case BATTERY:
                return "In the box that appears, choose ALLOW so ReplyMate may always"
                    + " run in the background. (If no box appears, find ReplyMate in the"
                    + " list and pick \"Don't optimize\" / Battery \u2192 Unrestricted.)";
            default:
                return "";   // POST_NOTIFICATIONS is an in-app runtime request
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

    /* ------------------------------------------------------------- screens */

    /** The exact system screen that grants this approval; null when there is no
     *  screen (POST_NOTIFICATIONS is an in-app runtime permission request).
     *  P-background-10: BATTERY is now the focused per-app approval dialog —
     *  never the generic all-apps battery list. */
    public static Intent screenIntent(Context ctx, Need n) {
        switch (n) {
            case NOTIFICATION_ACCESS:
                return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            case BATTERY:
                return new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + ctx.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            default:
                return null;
        }
    }

    /** Fallback #1 for BATTERY: the all-apps battery optimization list (OEMs that
     *  strip the request dialog still ship this). */
    public static Intent batteryListIntent() {
        return new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Fallback #2 for BATTERY: ReplyMate's own app-details page (where
     *  "Battery \u2192 Unrestricted" lives on Android 12+). */
    public static Intent appDetailsIntent(Context ctx) {
        return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:" + ctx.getPackageName()))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /** Maker-specific battery/autostart screens (Tecno, Infinix, Xiaomi, Oppo,
     *  Vivo, Huawei, Samsung…). Components below are the widely-documented
     *  candidates — and HONESTY BY CONSTRUCTION: each is checked with
     *  resolveActivity, so a candidate that doesn't exist on this device simply
     *  never surfaces. We never claim a maker screen we can't actually open. */
    private static final String[][] OEM_SCREENS = {
        // Transsion "Phone Master" (TECNO / Infinix / itel — very common in NG)
        {"com.transsion.phonemaster", "com.transsion.phonemaster.autostart.AutoStartManagerActivity"},
        {"com.transsion.phonemaster", "com.transsion.phonemaster.ato.AtoolActivity"},
        // Xiaomi / Redmi / POCO (MIUI autostart manager)
        {"com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"},
        // OPPO / realme (ColorOS 12+ and older safecenter startup lists)
        {"com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"},
        {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
        // vivo
        {"com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"},
        // Huawei / Honor protected apps
        {"com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"},
        // Samsung Device care battery
        {"com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"},
        // OnePlus app-launch manager
        {"com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
        // Asus Mobile Manager
        {"com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity"},
    };

    /** The first maker screen that REALLY resolves on this device, else null. */
    public static Intent oemBackgroundIntent(Context ctx) {
        for (String[] pair : OEM_SCREENS) {
            Intent i = new Intent()
                .setClassName(pair[0], pair[1])
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (canLaunch(ctx, i)) return i;
        }
        return null;
    }

    /** ResolveActivity guard — false instead of a crash on weird OEM builds.
     *  Goes through PackageManager DIRECTLY: Intent#resolveActivity short-circuits
     *  explicit-component intents without asking the manager, which would fake
     *  "this maker screen exists" on every device — the manager is the truth. */
    public static boolean canLaunch(Context ctx, Intent i) {
        if (ctx == null || i == null) return false;
        try {
            return ctx.getPackageManager().resolveActivity(i, 0) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Guarded launch; false when the system refuses (never thrown). */
    public static boolean launch(Context ctx, Intent i) {
        try {
            ctx.startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** P-background-10: drive the CORRECT battery approval flow — focused request
     *  dialog first, then the all-apps list, then app-details. Returns a short
     *  human label of what actually opened ("" = nothing could; caller must say
     *  so honestly instead of pretending). */
    public static String openBatteryApprovalFlow(Context ctx) {
        Intent primary = screenIntent(ctx, Need.BATTERY);
        if (canLaunch(ctx, primary) && launch(ctx, primary)) {
            return "system background-activity approval";
        }
        Intent list = batteryListIntent();
        if (canLaunch(ctx, list) && launch(ctx, list)) {
            return "battery optimization list — find ReplyMate, choose \"Don't optimize\"";
        }
        Intent details = appDetailsIntent(ctx);
        if (canLaunch(ctx, details) && launch(ctx, details)) {
            return "ReplyMate app info — set Battery \u2192 Unrestricted";
        }
        return "";
    }
}
