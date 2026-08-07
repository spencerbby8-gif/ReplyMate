package com.replymate.app.listener;

import android.app.Notification;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;
import com.replymate.core.listener.RawNotif;
import java.util.List;

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
    private static final String K_PERSON_KEY = "key";
    private static final String K_PERSON_URI = "uri";

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
        // P-ux-fix status gate evidence. Same semantics as StatusBarNotification.isOngoing()
        // (ongoing-event || foreground-service) but read from the Notification's own
        // flags field — that is where the system copies them from, and unlike the SBN
        // field it survives Robolectric (its instrumented SBN never stores flags).
        raw.ongoing = (n.flags & (Notification.FLAG_ONGOING_EVENT
            | Notification.FLAG_FOREGROUND_SERVICE)) != 0;

        Bundle x = n.extras;
        if (x == null) return raw;              // still carries package/category/postTime

        raw.title = chars(x.getCharSequence(Notification.EXTRA_TITLE));
        raw.text = chars(x.getCharSequence(Notification.EXTRA_TEXT));
        raw.bigText = chars(x.getCharSequence(Notification.EXTRA_BIG_TEXT));
        raw.convTitle = chars(x.getCharSequence(K_CONV_TITLE));
        raw.ownerName = firstNonBlank(
            personName(safeBundle(x, K_USER)),
            chars(x.getCharSequence(K_SELF_NAME)));
        raw.ownerKey = ownerPersonField(x, K_PERSON_KEY);
        raw.conversationId = shortcutIdOf(n);
        raw.group = x.containsKey(K_IS_GROUP)
            ? Boolean.valueOf(x.getBoolean(K_IS_GROUP, false)) : null;
        raw.progressMax = x.getInt(Notification.EXTRA_PROGRESS_MAX, 0);

        Object msgs = x.get(K_MESSAGES);
        Parcelable[] arr = msgs instanceof Parcelable[] ? (Parcelable[]) msgs : null;
        if (arr != null) {
            for (Parcelable item : arr) {
                if (!(item instanceof Bundle)) continue;
                RawNotif.Entry e = entry((Bundle) item, sbn.getPostTime());
                if (e != null) raw.messages.add(e);
            }
        }
        extractActions(n, raw);
        raw.sbnKey = keyOf(sbn);
        return raw;
    }

    /** P-background (extended in P-background-3, docs-complete): dumb copy of
     *  notification actions for the capability check. Quick-reply may legitimately
     *  live in TWO documented places, so BOTH are scanned:
     *    1. Notification.actions (standard, SRC_STANDARD)
     *    2. Notification.WearableExtender actions (SRC_WEARABLE) — the channel
     *       Android Auto/Wear reply through; several messengers put their ONLY
     *       RemoteInput-bearing action here, with a plain UI-opening "Reply"
     *       action in the standard array. Missing the wearable list is exactly
     *       how a device can show "Reply" while a listener sees no usable action.
     *  Reads ONLY shape (title / index / source / free-form RemoteInput + result
     *  key) — never the PendingIntent, which the assistant re-resolves live at
     *  send time. One malformed action degrades that action only. */
    private static void extractActions(Notification n, RawNotif raw) {
        Notification.Action[] acts;
        try {
            acts = n.actions;
        } catch (RuntimeException e) {
            acts = null;
        }
        if (acts != null) {
            for (int i = 0; i < acts.length; i++) {
                copyAction(acts[i], i, RawNotif.ActionRef.SRC_STANDARD, raw);
            }
        }
        try {
            List<Notification.Action> wearable =
                new Notification.WearableExtender(n).getActions();
            if (wearable != null) {
                for (int i = 0; i < wearable.size(); i++) {
                    copyAction(wearable.get(i), i, RawNotif.ActionRef.SRC_WEARABLE, raw);
                }
            }
        } catch (RuntimeException ignored) {
            // absent/broken wearable extensions must never affect standard extraction
        }
    }

    private static void copyAction(Notification.Action a, int index, int source, RawNotif raw) {
        try {
            if (a == null) return;
            RawNotif.ActionRef ref = new RawNotif.ActionRef();
            ref.title = chars(a.title);
            ref.index = index;
            ref.source = source;
            android.app.RemoteInput[] ris = a.getRemoteInputs();
            if (ris != null) {
                for (android.app.RemoteInput ri : ris) {
                    if (ri != null && ri.getAllowFreeFormInput()
                            && ri.getResultKey() != null
                            && !ri.getResultKey().trim().isEmpty()) {
                        ref.remoteFreeForm = true;
                        ref.resultKey = ri.getResultKey();
                        break;
                    }
                }
            }
            raw.actions.add(ref);
        } catch (RuntimeException ignored) {
            // keep scanning the remaining actions
        }
    }

    private static String keyOf(StatusBarNotification sbn) {
        try {
            String k = sbn.getKey();   // API 20+
            return k == null ? "" : k;
        } catch (RuntimeException e) {
            return "";
        }
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
            e.senderKey = senderPersonField(mb, K_PERSON_KEY);
            e.senderUri = senderPersonField(mb, K_PERSON_URI);
            e.hasAttachment = mb.get(K_URI) != null || mb.get(K_MIME) != null;
            // P-audit-deep: keep the attachment reference + MIME (never opened here —
            // extraction only, so the media pipeline can record WHAT is attached).
            e.mimeType = chars(bundleChars(mb, K_MIME));
            e.dataUri = uriString(mb.get(K_URI));
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
        return personField(person, K_PERSON_NAME);
    }

    /** Any string-ish field from a Person bundle (name/key/uri), defensively. */
    private static String personField(Bundle person, String key) {
        if (person == null) return null;
        try {
            CharSequence v = person.getCharSequence(key);
            if (v != null) return v.toString();
            Object raw = person.get(key);
            return raw == null ? null : String.valueOf(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Owner fields from "android.messagingUser": same dual delivery shape as
     *  sender_person (Bundle on 24-27/compat, framework Person Parcelable on 28+). */
    private static String ownerPersonField(Bundle x, String field) {
        Object raw;
        try {
            raw = x.get(K_USER);
        } catch (RuntimeException e) {
            return null;
        }
        if (raw instanceof Bundle) return personField((Bundle) raw, field);
        if (Build.VERSION.SDK_INT >= 28 && raw instanceof android.app.Person) {
            android.app.Person p = (android.app.Person) raw;
            if (K_PERSON_NAME.equals(field)) {
                CharSequence name = p.getName();
                return name == null ? null : name.toString();
            }
            if (K_PERSON_KEY.equals(field)) return p.getKey();
            if (K_PERSON_URI.equals(field)) return p.getUri();
        }
        return null;
    }

    /** Any field (name/key/uri) from the sender_person value, whichever runtime shape
     *  the POSTING app wrote: a Bundle (API 24-27 MessagingStyle / compat) or a
     *  framework android.app.Person Parcelable (API 28+). Defensive throughout. */
    private static String senderPersonField(Bundle mb, String field) {
        Object raw;
        try {
            raw = mb.get(K_SENDER_PERSON);
        } catch (RuntimeException badParcel) {
            return null;
        }
        if (raw instanceof Bundle) {
            return personField((Bundle) raw, field);
        }
        if (Build.VERSION.SDK_INT >= 28 && raw instanceof android.app.Person) {
            android.app.Person p = (android.app.Person) raw;
            if (K_PERSON_NAME.equals(field)) {
                CharSequence name = p.getName();
                return name == null ? null : name.toString();
            }
            if (K_PERSON_KEY.equals(field)) return p.getKey();
            if (K_PERSON_URI.equals(field)) return p.getUri();
        }
        return null;
    }

    /** Native conversation identifier the SOURCE app published with the notification:
     *  the shortcut id (API 29+ conversations). WhatsApp IDs its threads here (JID);
     *  other apps use their own (channel/room/thread ids). Never synthesized. */
    private static String shortcutIdOf(Notification n) {
        if (Build.VERSION.SDK_INT < 29) return null;
        try {
            return n.getShortcutId();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static CharSequence bundleChars(Bundle b, String key) {
        try {
            return b.getCharSequence(key);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Uri Parcelable (or plain string) → display string; never dereferenced. */
    private static String uriString(Object o) {
        if (o == null) return null;
        return String.valueOf(o);
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
