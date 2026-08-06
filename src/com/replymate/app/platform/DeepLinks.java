package com.replymate.app.platform;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import com.replymate.core.listener.ChatLink;
import com.replymate.core.model.Channel;

/** Opens a chat flow in the source app using ONLY official, documented surfaces (P3):
 *    1. an official share/deep-link URL when the app publishes one (ChatLink), or
 *    2. the app's own launcher activity as a safe fallback.
 *  No Accessibility, no intent extras into private activities, no unofficial APIs.
 *  Returns false when nothing could be launched (caller then copies the draft to
 *  the clipboard and tells the user to paste it). */
public final class DeepLinks {

    private DeepLinks() { }

    public static boolean openChat(Context ctx, Channel channel, String displayName,
                                   String shareText, String fallbackPackage) {
        if (ctx == null) return false;

        String url = ChatLink.launchUrl(channel, displayName, shareText);
        if (url != null) {
            try {
                Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(view);
                return true;
            } catch (ActivityNotFoundException noHandler) {
                // fall through to the package launcher
            } catch (RuntimeException boom) {
                // some OEMs throw SecurityException for cross-app starts — degrade
            }
        }

        if (fallbackPackage != null && !fallbackPackage.trim().isEmpty()) {
            try {
                Intent launch = ctx.getPackageManager()
                    .getLaunchIntentForPackage(fallbackPackage);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(launch);
                    return true;
                }
            } catch (RuntimeException boom) {
                // fall through: nothing launchable
            }
        }
        return false;
    }

    /** Is this package installed (for the Sources screen badges)? */
    public static boolean packageExists(Context ctx, String packageName) {
        if (ctx == null || packageName == null || packageName.isEmpty()) return false;
        try {
            ctx.getPackageManager().getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException nnf) {
            return false;
        } catch (RuntimeException boom) {
            return false;
        }
    }

    /** Play-Store page for a not-installed watched app (official install surface). */
    public static boolean openStorePage(Context ctx, String packageName) {
        if (ctx == null || packageName == null || packageName.isEmpty()) return false;
        try {
            Intent i = new Intent(Intent.ACTION_VIEW,
                Uri.parse("market://details?id=" + packageName));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (RuntimeException boom) {
            return false;
        }
    }
}
