package gr.prosfora.app.sync

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import gr.prosfora.app.debug.DebugLog

class DriveAutoSyncAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != "gr.prosfora.app.action.DRIVE_AUTO_SYNC") return
        val app = context.applicationContext
        val wifi = DriveAutoSyncScheduler.isWifi(app)
        DebugLog.log("auto-sync", "alarm fired; wifi=$wifi")
        // Η κοινόχρηστη βάση συγχρονίζεται σε κάθε τικ: είναι λίγα KB και είναι
        // αυτό που κάνει τις συσκευές να βλέπουν τα ίδια. Η σάρωση του Drive
        // —που κατεβάζει και διαβάζει αρχεία— κρατάει τον δικό της ρυθμό.
        SheetPushWorker.push(app)
        if (DriveAutoSyncScheduler.fullScanDue(app, wifi)) DriveAutoSyncWorker.enqueueNow(app)
        // Re-evaluate the transport after each alarm so switching Wi-Fi/mobile
        // updates the interval without needing the app UI to be open.
        DriveAutoSyncScheduler.schedule(app)
    }
}
