package uk.hairyfred.libreshock.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import uk.hairyfred.libreshock.MainActivity

/**
 *  Posts a system notification when the watch reports an alarm is firing,
 *  with Stop and Snooze actions that route back to the running app via
 *  broadcasts. Lightweight by design: requires the app process to be alive
 *  to receive the watch's BLE notification in the first place.
 */
object AlarmNotifier {
    const val CHANNEL_ID = "alarm_firing"
    const val NOTIFICATION_ID = 1001

    const val ACTION_STOP = "uk.hairyfred.libreshock.ACTION_STOP_ALARM"
    const val ACTION_SNOOZE = "uk.hairyfred.libreshock.ACTION_SNOOZE_ALARM"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService<NotificationManager>() ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Alarm firing",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Shows when the watch reports an alarm is going off."
            enableVibration(true)
            setBypassDnd(false)
        }
        nm.createNotificationChannel(channel)
    }

    fun notifyFiring(context: Context, alarmId: Int) {
        ensureChannel(context)
        val nm = context.getSystemService<NotificationManager>() ?: return

        val openApp = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            context, 0, openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopPi = PendingIntent.getBroadcast(
            context, 1, Intent(ACTION_STOP).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snoozePi = PendingIntent.getBroadcast(
            context, 2, Intent(ACTION_SNOOZE).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Alarm firing")
            .setContentText("Alarm $alarmId is going off")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setOngoing(true)
            .setAutoCancel(false)
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .addAction(0, "Snooze", snoozePi)
            .build()

        nm.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        context.getSystemService<NotificationManager>()?.cancel(NOTIFICATION_ID)
    }
}
