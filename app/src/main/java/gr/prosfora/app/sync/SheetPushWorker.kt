package gr.prosfora.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import gr.prosfora.app.debug.DebugLog
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.SheetsClient
import java.util.concurrent.TimeUnit

/**
 * Συγχρονισμός **μόνο** της κοινόχρηστης βάσης, χωρίς σάρωση του Drive.
 *
 * Υπάρχει για να φεύγει μια αλλαγή αμέσως. Ο πλήρης κύκλος κατεβάζει και
 * διαβάζει αρχεία, οπότε δεν μπορεί να τρέχει σε κάθε τικ ενός checkbox —
 * έτσι μια πληρωμή που σημειωνόταν στο ένα κινητό περίμενε τον επόμενο κύκλο,
 * και με δεδομένα κινητής αυτό ήταν μία ώρα.
 *
 * Το φύλλο διαβάζεται **και** γράφεται, άρα η ίδια διαδρομή φέρνει και τις
 * αλλαγές των άλλων. Η συγχώνευση κρίνεται από τις ώρες ενημέρωσης, οπότε
 * όποιος έγραψε τελευταίος κερδίζει ανεξάρτητα από το ποιος συγχρόνισε πρώτος.
 */
class SheetPushWorker(appContext: Context, params: WorkerParameters) :
    CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val settings = GoogleSettings(applicationContext)
        if (settings.spreadsheetId.isNullOrBlank()) return Result.success()

        val token = runCatching { BackgroundToken.withoutUi(applicationContext) }
            .getOrElse { error ->
                DebugLog.log("sync", "push: δεν πήρα token · ${error.reason()}")
                return Result.retry()
            } ?: return Result.success()

        return runCatching {
            val summary = SheetSync(applicationContext, SheetsClient(token), settings).sync().summary
            DebugLog.log("sync", "push βάσης · $summary")
            Result.success()
        }.getOrElse { error ->
            DebugLog.log("sync", "push βάσης απέτυχε · ${error.reason()}")
            Result.retry()
        }
    }

    private fun Throwable.reason(): String = message ?: this::class.java.simpleName

    companion object {
        private const val WORK_NAME = "prosfora-sheet-push"

        /**
         * Στέλνει ό,τι άλλαξε, τώρα.
         *
         * `APPEND_OR_REPLACE` και όχι `KEEP`: αν τρέχει ήδη ένας συγχρονισμός,
         * η αλλαγή που μόλις έγινε δεν προλαβαίνει να μπει σε αυτόν, οπότε
         * χρειάζεται δεύτερο πέρασμα αμέσως μετά — αλλιώς θα περίμενε τον
         * επόμενο κύκλο. Ένα μόνο πέρασμα μπαίνει στη σειρά.
         */
        fun push(context: Context) {
            val app = context.applicationContext
            if (GoogleSettings(app).spreadsheetId.isNullOrBlank()) return
            WorkManager.getInstance(app).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<SheetPushWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    )
                    .setInitialDelay(2, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
