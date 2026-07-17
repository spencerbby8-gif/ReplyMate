package com.replymate.core.notifications

import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * Registered now so Android can grant notification-listener access during onboarding.
 * Ingestion is deliberately introduced in the next approved slice; no notification
 * content is read, stored, or transmitted by this Phase 2 shell.
 */
class ReplyMateNotificationListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "Notification listener connected; ingestion is not enabled in this slice.")
    }
    override fun onListenerDisconnected() {
        Log.i(TAG, "Notification listener disconnected.")
        super.onListenerDisconnected()
    }
    private companion object { const val TAG = "ReplyMateNotification" }
}
