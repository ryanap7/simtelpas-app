package com.sdn.simtelpas.kiosk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.sdn.simtelpas.R
import com.sdn.simtelpas.ui.MainActivity

/**
 * Lightweight foreground service that relaunches MainActivity if its task
 * gets removed unexpectedly (crash/ANR/swipe-from-recents edge cases). Does
 * NOT protect against Settings > Force Stop or `adb shell am force-stop`
 * (those kill this service's process too) — those routes are blocked
 * instead by the lock-task package allow-list and the DISALLOW_* user
 * restrictions set in KioskModeManager.
 */
class KioskWatchdogService : Service() {

    companion object {
        private const val CHANNEL_ID      = "kiosk_guard"
        private const val NOTIFICATION_ID = 1
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        START_STICKY

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (KioskModeManager.isAdminModeActive(applicationContext)) return

        try {
            startActivity(
                Intent(applicationContext, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        } catch (_: Exception) {
        }
    }

    private fun buildNotification(): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Kiosk Guard",
                NotificationManager.IMPORTANCE_MIN,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(getString(R.string.app_name))
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .build()
    }
}
