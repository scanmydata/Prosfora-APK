package gr.prosfora.app.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import gr.prosfora.app.debug.DebugLog

/**
 * Adaptive background scheduler:
 * Wi‑Fi -> best-effort check every minute.
 * Cellular -> check every hour.
 *
 * AlarmManager is used instead of WorkManager because WorkManager's minimum
 * periodic interval is 15 minutes. Android may still defer inexact alarms,
 * especially under Doze/battery saving, so one minute is a target, not a hard
 * delivery guarantee.
 */
object DriveAutoSyncScheduler {
    private const val REQUEST_CODE = 7401
    private const val ACTION = "gr.prosfora.app.action.DRIVE_AUTO_SYNC"
    private const val WIFI_INTERVAL_MS = 60_000L
    private const val CELLULAR_INTERVAL_MS = 60L * 60L * 1000L

    fun schedule(context: Context) {
        val app = context.applicationContext
        val alarm = app.getSystemService(AlarmManager::class.java) ?: return
        val pending = pendingIntent(app)
        alarm.cancel(pending)

        val interval = if (isWifi(app)) WIFI_INTERVAL_MS else CELLULAR_INTERVAL_MS
        alarm.setInexactRepeating(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + interval,
            interval,
            pending,
        )
        DebugLog.log("auto-sync", "scheduler interval=${if (interval == WIFI_INTERVAL_MS) "1 minute / Wi-Fi" else "1 hour / mobile data"}")
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        app.getSystemService(AlarmManager::class.java)?.cancel(pendingIntent(app))
    }

    fun isWifi(context: Context): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun pendingIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            Intent(context, DriveAutoSyncAlarmReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
