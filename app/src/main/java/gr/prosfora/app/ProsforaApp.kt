package gr.prosfora.app

import android.app.Application
import gr.prosfora.app.debug.DebugLog
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.sync.DriveAutoSyncWorker

class ProsforaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val settings = GoogleSettings(this)
        DebugLog.configure(this, settings.debugLogging)

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            DebugLog.logException("crash", "Απρόβλεπτο crash στο thread=${thread.name}", error)
            previous?.uncaughtException(thread, error)
        }
        DriveAutoSyncWorker.schedule(this)
        DebugLog.log("app", "Εφαρμογή ξεκίνησε · automatic Drive sync scheduled")
    }
}
