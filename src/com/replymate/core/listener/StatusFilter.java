package com.replymate.core.listener;

/** Filters non-message app self-status notifications (P-ux-fix). WhatsApp backup,
 *  "checking for new messages", syncing/restoring/progress/digest items must NEVER
 *  create or update a chat — they're the app's own housekeeping, not a person.
 *
 *  Safety rule (guarded by StatusFilterTest): the filter ONLY fires when the
 *  notification carries NO conversation evidence —
 *    • no MessagingStyle message history, AND
 *    • a title that is empty, the app label itself, or the raw package name.
 *  A real human message ALWAYS arrives with sender/conversation context, so it can
 *  never be swallowed. Words like "preparing" are deliberately NOT filtered: only
 *  machine-styled status phrases are, and only under the self-title gate. */
public final class StatusFilter {

    /** Machine-styled status phrases (normalized lowercase). Tuned narrow on purpose:
     *  a false negative (a stray status card stored) is recoverable; a false
     *  positive (a swallowed human text) is not. */
    private static final String[] PHRASES = {
        "backup", "back up", "backing up", "backed up", "restoring", "restore ",
        "checking for new", "checking messages", "syncing", "sync complete",
        "sync failed", "reconnecting", "waiting for network", "waiting for wi-fi",
        "download in progress", "downloading update", "downloading…", "update available",
        "installing update", "optimizing", "cleaning up", "verifying your",
        "encrypting", "may take a while", "tap to resume", "tap to retry",
        "unread message", "unread chat", "you have new notifications",
        "notifications are on", "notifications for", "finishing up",
        "getting your messages", "loading chats", "importing", "exporting",
        "updating your", "transferring your", "preparing backup", "cloud backup",
        "google drive", "storage almost full", "free up space"
    };

    private StatusFilter() { }

    /** True ⇒ ignore as app self-status (never a chat). See class doc for the gate. */
    public static boolean isSelfStatus(RawNotif raw, String appLabel) {
        if (raw == null) return false;
        if (!raw.messages.isEmpty()) return false;          // conversation history wins

        String title = norm(raw.title);
        String label = norm(appLabel);
        String pkg = norm(raw.packageName);
        boolean selfTitle = title.isEmpty()
            || (!label.isEmpty() && title.equals(label))
            || (!pkg.isEmpty() && title.equals(pkg));
        if (!selfTitle) return false;                        // titled like a PERSON/chat

        if (raw.ongoing) return true;                        // persistent app work status
        if (raw.progressMax > 0) return true;                // progress bar housekeeping

        String text = norm(firstNonBlank(raw.text, raw.bigText));
        if (text.isEmpty()) return true;                     // self-titled, no body: status card
        for (String p : PHRASES) {
            if (text.contains(p)) return true;
        }
        return false;
    }

    static String norm(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase(java.util.Locale.US).replaceAll("\\s+", " ");
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.trim().isEmpty() ? a : b;
    }
}
