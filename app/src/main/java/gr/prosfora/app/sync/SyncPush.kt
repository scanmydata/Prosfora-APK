package gr.prosfora.app.sync

import android.content.Context
import gr.prosfora.app.debug.DebugLog
import gr.prosfora.app.google.AccessTokenCache
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.SheetsClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Στέλνει μια αλλαγή στην κοινόχρηστη βάση **τώρα**, όσο η εφαρμογή τρέχει.
 *
 * Το [SheetPushWorker] έκανε τη δουλειά στο παρασκήνιο, αλλά εξαρτιόταν από ένα
 * token χωρίς οθόνη — και όταν η Google ζητούσε έγκριση, δεν έτρεχε τίποτα. Το
 * αποτέλεσμα ήταν αυτό που φαινόταν στην πράξη: η συσκευή που σημείωνε μια
 * πληρωμή δεν ενημέρωνε τη βάση ώσπου να πατήσει κάποιος συγχρονισμό.
 *
 * Εδώ χρησιμοποιείται το token που έχει ήδη πάρει η εφαρμογή
 * ([AccessTokenCache]). Όταν δεν υπάρχει —ή όταν αποτύχει το δίκτυο— η δουλειά
 * παραδίδεται στο worker, που περιμένει σύνδεση και προσπαθεί μόνο του.
 *
 * Το [SheetSync] διαβάζει **και** γράφει, οπότε η ίδια διαδρομή φέρνει και τις
 * αλλαγές των άλλων συσκευών.
 */
object SyncPush {
    /** Όσο κρατάει η ριπή: πολλά πατήματα ή πλήκτρα γίνονται ένας συγχρονισμός. */
    private const val COALESCE_MS = 2_500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Any()
    private var queued: Job? = null

    /**
     * @param delayMillis πόσο να περιμένει πριν στείλει, ώστε να μαζευτούν οι
     * διαδοχικές αλλαγές. Μηδέν για άμεσο συγχρονισμό (π.χ. περιοδικός έλεγχος).
     */
    fun now(context: Context, delayMillis: Long = COALESCE_MS) {
        val app = context.applicationContext
        if (GoogleSettings(app).spreadsheetId.isNullOrBlank()) return
        synchronized(gate) {
            // Ακυρώνεται μόνο η αναμονή· ένας συγχρονισμός που ξεκίνησε
            // τελειώνει, αλλιώς το φύλλο θα έμενε μισογραμμένο
            queued?.cancel()
            queued = scope.launch {
                delay(delayMillis)
                withContext(NonCancellable) { send(app) }
            }
        }
    }

    private suspend fun send(app: Context) {
        val settings = GoogleSettings(app)
        val token = AccessTokenCache.valid() ?: run {
            DebugLog.log("sync", "άμεσος συγχρονισμός: χωρίς token στη μνήμη · παραδίδεται στο worker")
            SheetPushWorker.push(app)
            return
        }
        runCatching { SheetSync(app, SheetsClient(token), settings).sync() }
            .onSuccess { report -> DebugLog.log("sync", "άμεσος συγχρονισμός βάσης · ${report.summary}") }
            .onFailure { error ->
                DebugLog.log("sync", "άμεσος συγχρονισμός απέτυχε · ${error.message ?: error::class.java.simpleName}")
                // Μπορεί να έληξε το token ή να έπεσε το δίκτυο: και στις δύο
                // περιπτώσεις το worker ξαναπροσπαθεί όταν υπάρχει σύνδεση
                AccessTokenCache.forget()
                SheetPushWorker.push(app)
            }
    }
}
