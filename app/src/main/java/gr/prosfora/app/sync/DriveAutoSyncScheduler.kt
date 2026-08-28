package gr.prosfora.app.sync

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import gr.prosfora.app.debug.DebugLog

/**
 * Adaptive background scheduler:
 * Wi‑Fi -> best-effort/exact check every minute when Android allows exact alarms.
 * Cellular -> check every hour.
 *
 * Without SCHEDULE_EXACT_ALARM Android may defer inexact alarms, especially under
 * Doze/battery saving. Exact alarm use is therefore conditional and has a safe fallback.
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

        if (isWifi(app)) {
            val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()
            if (exact) {
                alarm.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + WIFI_INTERVAL_MS,
                    pending,
                )
                DebugLog.log("auto-sync", "scheduler Wi-Fi: exact alarm στόχος 1 λεπτό")
            } else {
                alarm.setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + WIFI_INTERVAL_MS,
                    WIFI_INTERVAL_MS,
                    pending,
                )
                DebugLog.log("auto-sync", "scheduler Wi-Fi: inexact fallback 1 λεπτό (exact alarm permission unavailable)")
            }
        } else {
            alarm.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + CELLULAR_INTERVAL_MS,
                CELLULAR_INTERVAL_MS,
                pending,
            )
            DebugLog.log("auto-sync", "scheduler mobile data: inexact 1 ώρα")
        }
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
