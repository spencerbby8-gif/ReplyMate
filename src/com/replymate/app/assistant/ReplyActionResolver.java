package com.replymate.app.assistant;

import android.app.Notification;
import com.replymate.core.listener.RawNotif;
import java.util.List;

/** Shared quick-reply action selection (P-background-5 extraction, P-background-7 use):
 *  the reply action is matched on a LIVE notification by its EXACT RemoteInput
 *  result key on the same documented surface (standard / wearable) — the stored
 *  index is only a first guess because layouts drift. Used both by the approve
 *  path and by target PendingIntent caching. */
public final class ReplyActionResolver {

    private ReplyActionResolver() { }

    /** The action carrying a free-form RemoteInput with EXACTLY {@code resultKey},
     *  or null (honest — never a best-guess). */
    public static Notification.Action select(Notification n, int source,
                                             String resultKey, int indexHint) {
        if (n == null || resultKey == null || resultKey.isEmpty()) return null;
        try {
            if (source == RawNotif.ActionRef.SRC_WEARABLE) {
                List<Notification.Action> wear;
                try {
                    wear = new Notification.WearableExtender(n).getActions();
                } catch (Throwable unreadable) {
                    return null;   // hostile/unreadable wearable extras → honest null
                }
                if (wear == null) return null;
                if (indexHint >= 0 && indexHint < wear.size()
                        && hasFreeFormKey(wear.get(indexHint), resultKey)) {
                    return wear.get(indexHint);
                }
                for (Notification.Action a : wear) {
                    if (hasFreeFormKey(a, resultKey)) return a;
                }
                return null;
            }
            Notification.Action[] std = n.actions;
            if (std == null) return null;
            if (indexHint >= 0 && indexHint < std.length
                    && hasFreeFormKey(std[indexHint], resultKey)) {
                return std[indexHint];
            }
            for (Notification.Action a : std) {
                if (hasFreeFormKey(a, resultKey)) return a;
            }
            return null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** P-background-8: try the CAPTURED surface first, then the other one. Apps
     *  occasionally move the reply action between standard/wearable across
     *  re-posts — the result KEY is still the identity; the surface is a hint. */
    public static Notification.Action selectAnySurface(Notification n, int sourceFirst,
                                                       String resultKey, int indexHint) {
        Notification.Action a = select(n, sourceFirst, resultKey, indexHint);
        if (a != null) return a;
        int other = sourceFirst == RawNotif.ActionRef.SRC_WEARABLE
            ? RawNotif.ActionRef.SRC_STANDARD : RawNotif.ActionRef.SRC_WEARABLE;
        return select(n, other, resultKey, indexHint);
    }

    /** True when the action carries a free-form RemoteInput with EXACTLY this key. */
    public static boolean hasFreeFormKey(Notification.Action a, String key) {
        if (a == null || key == null) return false;
        android.app.RemoteInput[] ris = a.getRemoteInputs();
        if (ris == null) return false;
        for (android.app.RemoteInput ri : ris) {
            if (ri != null && ri.getAllowFreeFormInput() && key.equals(ri.getResultKey())) {
                return true;
            }
        }
        return false;
    }

    /** A bare RemoteInput rebuilt from the CACHED result key — used to fill the
     *  official results structure when the original Action object is gone but its
     *  PendingIntent survived the notification's dismissal (P-background-7). */
    public static android.app.RemoteInput[] rebuiltInputs(String resultKey) {
        return new android.app.RemoteInput[] {
            new android.app.RemoteInput.Builder(resultKey).build()
        };
    }
}
