package gr.prosfora.app

import android.app.Application
import gr.prosfora.app.debug.DebugLog
import gr.prosfora.app.google.GoogleSettings

class ProsforaApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // Πριν από οτιδήποτε άλλο: αν ο χρήστης έχει ανοιχτή την καταγραφή,
        // πρέπει να πιάσει και το πρώτο άνοιγμα αρχείου, όχι μόνο ό,τι γίνεται
        // αφού μπει στις ρυθμίσεις
        DebugLog.configure(this, GoogleSettings(this).debugLogging)
    }
}
