package io.github.hamzabhai.jhatpat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.github.hamzabhai.jhatpat.MainActivity
import io.github.hamzabhai.jhatpat.R

object Notifications {
    const val ID = 1
    private const val CHANNEL = "sharing"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Sharing", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while JhatPat is sharing"
                    setShowBadge(false)
                },
            )
        }
    }

    fun build(context: Context, state: SharingState): Notification {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context, 1, Intent(context, SharingService::class.java).setAction(SharingService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val active = state is SharingState.Starting || state is SharingState.Running
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(NotificationText.title(state))
            .setContentText(NotificationText.body(state).ifEmpty { null })
            .setContentIntent(open)
            .setOngoing(active)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        if (active) {
            builder.addAction(0, "Open", open)
            builder.addAction(0, "Stop", stop)
        }
        return builder.build()
    }
}
