package com.streamcontrols.app.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.streamcontrols.app.MainActivity
import com.streamcontrols.app.R

private const val CHANNEL_ID = "account_connections"
private const val NOTIFICATION_ID = 1001

internal fun accountNotificationsAvailable(context: Context): Boolean = try {
    val manager = NotificationManagerCompat.from(context)
    val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    permitted && manager.areNotificationsEnabled() &&
        manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE
} catch (_: RuntimeException) { false }

internal fun showTwitchConnectedNotification(context: Context) {
    try { postTwitchConnectedNotification(context) } catch (_: RuntimeException) {
        // Notification failures must not undo or misreport a successfully saved connection.
    }
}

private fun postTwitchConnectedNotification(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
    val manager = NotificationManagerCompat.from(context)
    if (!manager.areNotificationsEnabled()) return
    val channel = NotificationChannel(CHANNEL_ID, "Account connections", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "Alerts when an account connects while you're authorizing in a browser."
    }
    manager.createNotificationChannel(channel)
    val returnIntent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val pending = PendingIntent.getActivity(context, 0, returnIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_broadcast)
        .setContentTitle("Twitch connected")
        .setContentText("Tap to return to Stream Controls.")
        .setContentIntent(pending)
        .setAutoCancel(true)
        .build()
    manager.notify(NOTIFICATION_ID, notification)
}

internal fun dismissTwitchConnectedNotification(context: Context) {
    NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
}
