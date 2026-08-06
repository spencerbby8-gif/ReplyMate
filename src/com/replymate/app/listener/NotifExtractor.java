package com.replymate.app.listener;

import android.app.Notification;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import com.replymate.core.listener.ListenerFilter;
import com.replymate.core.listener.NotifEvent;
import com.replymate.core.model.Channel;
import java.util.ArrayList;
import java.util.List;

/** THIN platform adapter: StatusBarNotification → List<NotifEvent>.
 *
 *  NOTE (framework-only build): NotificationCompat's static extractor is AndroidX-only,
 *  so we read the framework's documented MessagingStyle extras directly — the same bundle
 *  both WhatsApp and Telegram write:
 *    "android.messages"             Parcelable[] of message Bundles
 *      "text"                       CharSequence  message body
 *      "time"                       long          message timestamp
 *      "sender"                     CharSequence  legacy sender name
 *      "sender_person"              Bundle (24-27/compat) or Person Parcelable (28+)
 *      "uri" / "type"               attachment markers
 *    "android.messagingUser"        Bundle Person of the device owner → "name"
 *    "android.selfDisplayName"      String  fallback owner display name
 *    "android.conversationTitle"    CharSequence conversation title (groups)
 *    "android.isGroupConversation"  boolean
 *  All decisions live in core.listener (unit-tested); this class only reads framework data. */
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

    public static List<NotifEvent> extract(StatusBarNotification sbn) {
        List<NotifEvent> out = new ArrayList<NotifEvent>();
        if (sbn == null || sbn.getNotification() == null) return out;

        Channel channel = ListenerFilter.channelForPackage(sbn.getPackageName());
        if (channel == null) return out;                    // not a watched app → ignore

        Notification n = sbn.getNotification();
        Bundle x = n.extras;
        if (x == null) return out;

        String convTitle = firstNonBlank(
            chars(x.getCharSequence(K_CONV_TITLE)),
            chars(x.getCharSequence(Notification.EXTRA_TITLE)));
        String ownerName = firstNonBlank(
            personName(x.getBundle(K_USER)),
            chars(x.getCharSequence(K_SELF_NAME)));
        boolean group = x.getBoolean(K_IS_GROUP, false);

        Object raw = x.get(K_MESSAGES);
        Parcelable[] msgs = raw instanceof Parcelable[] ? (Parcelable[]) raw : null;

        if (msgs == null || msgs.length == 0) {
            // Fallback: notification without MessagingStyle history (single text payload).
            CharSequence text = x.getCharSequence(Notification.EXTRA_TEXT);
            if (text == null || text.toString().trim().isEmpty()) return out;
            NotifEvent e = base(channel, sbn, convTitle, ownerName, group);
            e.senderName = convTitle;
            e.text = text.toString();
            e.timestampMs = sbn.getPostTime();
            out.add(e);
            return out;
        }

        for (Parcelable item : msgs) {
            if (!(item instanceof Bundle)) continue;
            Bundle mb = (Bundle) item;
            try {
                NotifEvent e = base(channel, sbn, convTitle, ownerName, group);
                CharSequence text = mb.getCharSequence(K_TEXT);
                e.text = text == null ? null : text.toString();
                long time = mb.getLong(K_TIME, 0L);
                e.timestampMs = time > 0 ? time : sbn.getPostTime();
                e.senderName = firstNonBlank(
                    senderName(mb),
                    chars(mb.getCharSequence(K_SENDER)));
                e.hasAttachment = mb.get(K_URI) != null || mb.get(K_MIME) != null;
                out.add(e);
            } catch (RuntimeException malformed) {
                // One malformed message bundle must never drop the whole
                // notification (silent-loss bug fixed in P2 repair pass).
                android.util.Log.w(TAG, "dropping one malformed message bundle", malformed);
            }
        }
        return out;
    }

    private static NotifEvent base(Channel channel, StatusBarNotification sbn,
                                   String convTitle, String ownerName, boolean group) {
        NotifEvent e = new NotifEvent();
        e.channel = channel;
        e.packageName = sbn.getPackageName();
        e.conversationTitle = convTitle;
        e.ownerName = ownerName;
        e.group = group;
        return e;
    }

    /** Owner display name from "android.messagingUser" — always a Bundle
     *  (framework writes Person.toBundle() into this extra; Person key "name"). */
    private static String personName(Bundle person) {
        if (person == null) return null;
        CharSequence name = person.getCharSequence(K_PERSON_NAME);
        return name == null ? null : name.toString();
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
