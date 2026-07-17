package com.replymate.core.notifications

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat

/** Reads only the user's OS-granted listener state; it does not inspect notifications. */
object NotificationAccess {
    fun isListenerEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun listenerSettingsIntent() = android.content.Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    fun listenerComponent(context: Context) = ComponentName(context, ReplyMateNotificationListenerService::class.java)
}
