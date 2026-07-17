package com.replymate.core.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.replymate.ReplyMateApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Supported Android listener only. It normalizes and records events; it never invokes AI or sends a reply. */
class ReplyMateNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onListenerConnected() { super.onListenerConnected(); Log.i(TAG, "Notification listener connected.") }
    override fun onNotificationPosted(sbn: StatusBarNotification) { val app=application as? ReplyMateApplication ?: return; scope.launch { app.notificationPipeline.process(sbn) } }
    override fun onListenerDisconnected() { Log.i(TAG, "Notification listener disconnected."); super.onListenerDisconnected() }
    private companion object { const val TAG = "ReplyMateNotification" }
}
