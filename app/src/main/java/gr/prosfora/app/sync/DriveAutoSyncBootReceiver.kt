package gr.prosfora.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import gr.prosfora.app.debug.DebugLog

class DriveAutoSyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            -> {
                DebugLog.log("auto-sync", "boot/package replacement; scheduling adaptive sync")
                DriveAutoSyncScheduler.schedule(context.applicationContext)
            }
        }
    }
}
