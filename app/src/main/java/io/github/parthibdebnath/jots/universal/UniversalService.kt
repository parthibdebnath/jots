package io.github.parthibdebnath.jots.universal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import io.github.parthibdebnath.jots.JotsApp
import io.github.parthibdebnath.jots.MainActivity
import io.github.parthibdebnath.jots.R

/**
 * Keeps the tablet with Jots while other apps are on screen. Only runs while "use the tablet in
 * other apps" is on; its notification has a Stop button.
 */
class UniversalService : Service() {
    private val app get() = application as JotsApp

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app.settings.update { it.copy(universal = it.universal.copy(enabled = false)) }
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(),
                if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
            )
        } catch (t: Throwable) {
            Log.w("UniversalService", "startForeground failed", t)
            stopSelf()
            return START_NOT_STICKY
        }
        app.universal.onServiceStarted()
        // Not sticky: if Android kills Jots, the tablet goes back to being a normal system pen
        // when it's replugged, rather than a half-restarted service holding it.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        app.universal.onServiceStopped()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Tablet in other apps", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Shown while the drawing tablet works outside Jots"
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, UniversalService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_tablet)
            .setContentTitle("Tablet works in all apps")
            .setContentText("Pen, keys and dial are driven by Jots")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        private const val CHANNEL = "universal"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "io.github.parthibdebnath.jots.STOP_UNIVERSAL"
    }
}
