package com.itantra.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import com.itantra.app.link.TextMessage

/**
 * Foreground service that keeps the process (and so [ReceiverHub]'s receivers)
 * alive while a receiver runs and the UI is not visible. It holds no logic of
 * its own: [ReceiverHub] starts and stops it. The ongoing notification offers
 * "Stop receiver".
 */
class ReceiverService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val hub = ReceiverHub.get(application)
        when (intent?.action) {
            ACTION_STOP_RECEIVERS -> {
                hub.stopReceivers() // the hub then stops this service
                return START_NOT_STICKY
            }
            ACTION_ACK_SOS -> {
                hub.acknowledgeSos()
                return START_NOT_STICKY
            }
        }
        Notifications.ensureChannels(this)
        val notification = NotificationCompat.Builder(this, Notifications.RECEIVER_CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(getString(R.string.receiver_notification_title))
            .setContentText(getString(R.string.receiver_notification_text))
            .setOngoing(true)
            .setContentIntent(Notifications.openApp(this, sos = false))
            .addAction(0, getString(R.string.stop_receiver), servicePendingIntent(this, ACTION_STOP_RECEIVERS))
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        try {
            ServiceCompat.startForeground(this, Notifications.RECEIVER_ID, notification, type)
            Log.i(TAG, "foreground receiver service started")
        } catch (e: Exception) {
            Log.w(TAG, "cannot enter foreground: $e")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "foreground receiver service stopped")
        super.onDestroy()
    }

    companion object {
        private const val TAG = "ReceiverService"
        const val ACTION_STOP_RECEIVERS = "com.itantra.app.STOP_RECEIVERS"
        const val ACTION_ACK_SOS = "com.itantra.app.ACK_SOS"

        fun start(app: Application) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    app.startForegroundService(Intent(app, ReceiverService::class.java))
                } else {
                    app.startService(Intent(app, ReceiverService::class.java))
                }
            } catch (e: Exception) {
                // e.g. ForegroundServiceStartNotAllowedException when started from the background.
                Log.w(TAG, "cannot start receiver service: $e")
            }
        }

        fun stop(app: Application) {
            app.stopService(Intent(app, ReceiverService::class.java))
        }

        fun servicePendingIntent(context: Context, action: String): PendingIntent = PendingIntent.getService(
            context,
            action.hashCode(),
            Intent(context, ReceiverService::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

/** SOS notification: high priority, alarm category, full-screen intent where Android allows it. */
object SosNotifier {
    fun show(context: Context, message: TextMessage) {
        Notifications.ensureChannels(context)
        val open = Notifications.openApp(context, sos = true)
        val notification = NotificationCompat.Builder(context, Notifications.SOS_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(context.getString(R.string.sos_notification_title))
            .setContentText(message.text)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    message.text + "\n" + (
                        message.location?.let { context.getString(R.string.sos_notification_location, it.lat, it.lon) }
                            ?: context.getString(R.string.sos_location_unavailable)
                        ),
                ),
            )
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setColor(0xFFC62828.toInt())
            .setOngoing(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, context.getString(R.string.acknowledge_sos), ReceiverService.servicePendingIntent(context, ReceiverService.ACTION_ACK_SOS))
            .build()
        val manager = NotificationManagerCompat.from(context)
        val fullScreenAllowed = canUseFullScreenIntent(context)
        try {
            manager.notify(Notifications.SOS_ID, notification)
            Log.i("Sos", "SOS notification posted (notificationsEnabled=${manager.areNotificationsEnabled()} fullScreenAllowed=$fullScreenAllowed)")
        } catch (e: SecurityException) {
            Log.w("Sos", "SOS notification not allowed: $e")
        }
    }

    fun cancel(context: Context) = NotificationManagerCompat.from(context).cancel(Notifications.SOS_ID)

    /** Android 14+ lets the user (or Play policy) deny full-screen intents; earlier versions allow them. */
    fun canUseFullScreenIntent(context: Context): Boolean =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()
}

object Notifications {
    const val RECEIVER_CHANNEL = "receiver"
    const val SOS_CHANNEL = "sos"
    const val RECEIVER_ID = 1
    const val SOS_ID = 2
    const val EXTRA_SOS = "com.itantra.app.SOS"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(RECEIVER_CHANNEL, context.getString(R.string.channel_receiver), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(SOS_CHANNEL, context.getString(R.string.channel_sos), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // SosAlarm plays the alarm itself, on the alarm stream
                enableVibration(true)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            },
        )
    }

    fun openApp(context: Context, sos: Boolean): PendingIntent = PendingIntent.getActivity(
        context,
        if (sos) 1 else 0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_SOS, sos),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
