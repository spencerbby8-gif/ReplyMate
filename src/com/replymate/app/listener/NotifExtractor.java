package com.replymate.app.listener;

import android.app.Notification;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import com.replymate.core.listener.RawNotif;

/** THIN platform adapter (P3): StatusBarNotification → RawNotif.
 *
 *  This class makes NO parsing decisions — it is a dumb, defensive copy of the
 *  framework's documented notification extras into a platform-neutral shape so
 *  that every parser is pure JVM and unit-testable (BLUEPRINT §1 layering).
 *  Keys read (same bundle WhatsApp/Telegram/Signal/Messenger/Google Messages write):
 *    "android.title" / "android.text" / "android.bigText"   EXTRA_TITLE / TEXT / BIG_TEXT
 *    Notification.category                                 "msg", "social", "promo", …
 *    "android.messages"             Parcelable[] of message Bundles
 *      "text"                       CharSequence  message body
 *      "time"                       long          message timestamp
 *      "sender"                     CharSequence  legacy sender name
 *      "sender_person"              Bundle (24-27/compat) or Person Parcelable (28+)
 *      "uri" / "type"               attachment markers
 *    "android.messagingUser"        Bundle Person of the device owner → "name"
 *    "android.selfDisplayName"      String  fallback owner display name
 *    "android.conversationTitle"    CharSequence conversation title (groups)
 *    "android.isGroupConversation"  boolean   (tri-state preserved: absent → null)
 *  One malformed element degrades that element only — never the whole notification. */
public final class NotifExtractor {

    private static final String TAG = "NotifExtractor";
    private static final String K_MESSAGES = "android.messages";
    private static final String K_TEXT = "text";
    private static final String K_TIME = "time";
    private static final String K_SENDER = "sender";
    private static final String K_SENDER_PERSON = "sender_person";
    private static final String K_PERSON_NAME = "name";
    private static final String K_USER = "android.messagingUser";
    private static final String K_SELF_NAME = "android.selfDisplayName";
    private static final String K_CONV_TITLE = "android.conversationTitle";
    private static final String K_IS_GROUP = "android.isGroupConversation";
    private static final String K_URI = "uri";
    private static final String K_MIME = "type";

    private NotifExtractor() { }

    /** Copy the notification into a RawNotif. Returns null only when there is
     *  literally nothing to read (no notification / no extras). */
    public static RawNotif toRaw(StatusBarNotification sbn) {
        if (sbn == null || sbn.getNotification() == null) return null;
        Notification n = sbn.getNotification();

        RawNotif raw = new RawNotif();
        raw.packageName = safePackage(sbn);
        raw.postTimeMs = sbn.getPostTime();
        raw.category = n.category;              // public field since API 21

        Bundle x = n.extras;
        if (x == null) return raw;              // still carries package/category/postTime

        raw.title = chars(x.getCharSequence(Notification.EXTRA_TITLE));
        raw.text = chars(x.getCharSequence(Notification.EXTRA_TEXT));
        raw.bigText = chars(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        raw.convTitle = chars(x.getCharSequence(K_CONV_TITLE));
        raw.ownerName = firstNonBlank(
            personName(safeBundle(x, K_USER)),
            chars(x.getCharSequence(K_SELF_NAME)));
        raw.group = x.containsKey(K_IS_GROUP)
            ? Boolean.valueOf(x.getBoolean(K_IS_GROUP, false)) : null;

        Object msgs = x.get(K_MESSAGES);
        Parcelable[] arr = msgs instanceof Parcelable[] ? (Parcelable[]) msgs : null;
        if (arr != null) {
            for (Parcelable item : arr) {
                if (!(item instanceof Bundle)) continue;
                RawNotif.Entry e = entry((Bundle) item, sbn.getPostTime());
                if (e != null) raw.messages.add(e);
            }
        }
        return raw;
    }

    /** One message entry; a malformed bundle degrades to null (skipped), never throws. */
    private static RawNotif.Entry entry(Bundle mb, long fallbackTs) {
        try {
            RawNotif.Entry e = new RawNotif.Entry();
            CharSequence text = mb.getCharSequence(K_TEXT);
            e.text = text == null ? null : text.toString();
            long time = mb.getLong(K_TIME, 0L);
            e.timestampMs = time > 0 ? time : fallbackTs;
            e.senderName = firstNonBlank(
                senderName(mb),
                chars(mb.getCharSequence(K_SENDER)));
            e.hasAttachment = mb.get(K_URI) != null || mb.get(K_MIME) != null;
            return e;
        } catch (RuntimeException malformed) {
            // One malformed message bundle must never drop the whole
            // notification (silent-loss bug fixed in P2 repair pass).
            android.util.Log.w(TAG, "dropping one malformed message bundle", malformed);
            return null;
        }
    }

    /** Owner display name from "android.messagingUser" — always a Bundle
     *  (framework writes Person.toBundle() into this extra; Person key "name"). */
    private static String personName(Bundle person) {
        if (person == null) return null;
        CharSequence name = person.getCharSequence(K_PERSON_NAME);
        return name == null ? null : name.toString();
    }

    /** getBundle() defensively: a mistyped value must not blow up extraction. */
    private static Bundle safeBundle(Bundle x, String key) {
        try {
            return x.getBundle(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Sender display name from the (API 28+) "sender_person" extra. The value is
     *  delivered as whichever type the POSTING app's runtime wrote:
     *    - a Bundle (API 24-27 MessagingStyle, or AndroidX compat <28), or
     *    - a framework android.app.Person Parcelable (API 28+), or
     *    - anything else (some OEM/compat variants) → we fall back to "sender".
     *  Read defensively: Bundle.get would log a framework typeWarning on mismatch;
     *  a lazy-parcel value may also throw BadParcelableException — both degrade to null. */
    private static String senderName(Bundle mb) {
        Object raw;
        try {
            raw = mb.get(K_SENDER_PERSON);
        } catch (RuntimeException badParcel) {
            android.util.Log.w(TAG, "unreadable sender_person, using legacy sender key", badParcel);
            return null;
        }
        if (raw instanceof Bundle) {
            CharSequence name = ((Bundle) raw).getCharSequence(K_PERSON_NAME);
            return name == null ? null : name.toString();
        }
        if (Build.VERSION.SDK_INT >= 28 && raw instanceof android.app.Person) {
            CharSequence name = ((android.app.Person) raw).getName();
            return name == null ? null : name.toString();
        }
        return null;
    }

    private static String safePackage(StatusBarNotification sbn) {
        try { return sbn.getPackageName(); } catch (RuntimeException e) { return ""; }
    }

    private static String chars(CharSequence cs) {
        return cs == null ? null : cs.toString();
    }

    private static String firstNonBlank(String... options) {
        for (String s : options) {
            if (s != null && s.trim().length() > 0) return s;
        }
        return null;
    }
}
