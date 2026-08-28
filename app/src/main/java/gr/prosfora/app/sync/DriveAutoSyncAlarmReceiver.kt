package gr.prosfora.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import gr.prosfora.app.debug.DebugLog

class DriveAutoSyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != "gr.prosfora.app.action.DRIVE_AUTO_SYNC") return
        val app = context.applicationContext
        DebugLog.log("auto-sync", "alarm fired; wifi=${DriveAutoSyncScheduler.isWifi(app)}")
        DriveAutoSyncWorker.enqueueNow(app)
        // Re-evaluate the transport after each alarm so switching Wi-Fi/mobile
        // updates the interval without needing the app UI to be open.
        DriveAutoSyncScheduler.schedule(app)
    }
}
