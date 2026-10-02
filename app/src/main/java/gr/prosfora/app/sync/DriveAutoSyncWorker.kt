package gr.prosfora.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import gr.prosfora.app.debt.DebtRepository
import gr.prosfora.app.debug.DebugLog
import gr.prosfora.app.notify.DriveNotifier
import java.util.concurrent.TimeUnit

/** Background συγχρονισμός που εκτελείται όταν χτυπήσει ο adaptive alarm. */
class DriveAutoSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        DebugLog.log("auto-sync", "έναρξη background sync")

        runCatching {
            val unpaid = DebtRepository(applicationContext).unpaidDebts()
            DriveNotifier.notifyUnpaidDebtsDaily(applicationContext, unpaid)
        }.onFailure {
            DebugLog.log("auto-sync", "daily unpaid reminder απέτυχε: ${it.stackTraceToString()}")
        }

        // Δύο διαφορετικές αποτυχίες, δύο διαφορετικές απαντήσεις: όταν η
        // Google ζητάει έγκριση από τον χρήστη, καμία επανάληψη δεν θα τη
        // δώσει και το work κλείνει. Όταν όμως σκάει το δίκτυο, το work πρέπει
        // να ξαναδοκιμάσει — αλλιώς ό,τι γράφτηκε offline μένει στη συσκευή.
        val attempt = runCatching { BackgroundToken.withoutUi(applicationContext) }
        val token = attempt.getOrElse { error ->
            DebugLog.log("auto-sync", "δεν πήρα token: ${error.stackTraceToString()}")
            return Result.retry()
        } ?: run {
            DebugLog.log("auto-sync", "χρειάζεται έγκριση χρήστη· το background sync σταματά")
            return Result.success()
        }

        return runCatching {
            val result = DriveSyncCoordinator.sync(
                context = applicationContext,
                accessToken = token,
                syncSheet = true,
            )
            DebugLog.log(
                "auto-sync",
                "τέλος background sync · debts=${result.importedDebts.size} unreadable=${result.unreadableDebts}",
            )
            Result.success()
        }.onFailure {
            DebugLog.log("auto-sync", "background sync απέτυχε: ${it.stackTraceToString()}")
        }.getOrElse { Result.retry() }
    }

    companion object {
        private const val WORK_NAME = "prosfora-drive-auto-sync-now"
        private const val PERIODIC_NAME = "prosfora-drive-auto-sync-periodic"

        /** Διατηρεί μόνο ένα sync κάθε φορά, ακόμη κι αν χτυπήσουν πολλά alarms. */
        fun enqueueNow(context: Context) {
            val app = context.applicationContext
            val request: OneTimeWorkRequest = OneTimeWorkRequestBuilder<DriveAutoSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setInitialDelay(0, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(app).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request,
            )
        }

        /**
         * Δίχτυ ασφαλείας κάθε 15 λεπτά — το ελάχιστο που επιτρέπει το Android.
         *
         * Τα alarms είναι το γρήγορο κανάλι αλλά και το εύθραυστο: χωρίς
         * δικαίωμα exact alarm το Doze τα καθυστερεί, και μια σάρωση που δεν
         * έτρεξε σημαίνει ότι ένα νέο PDF στον κοινόχρηστο φάκελο δεν το είδε
         * καμία συσκευή. Το WorkManager επιβιώνει σε Doze και σε επανεκκίνηση.
         */
        fun ensurePeriodic(context: Context) {
            val app = context.applicationContext
            WorkManager.getInstance(app).enqueueUniquePeriodicWork(
                PERIODIC_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DriveAutoSyncWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    )
                    .build(),
            )
        }

        /** Συμβατότητα με το παλιό startup call. */
        fun schedule(context: Context) {
            ensurePeriodic(context)
            DriveAutoSyncScheduler.schedule(context)
        }
    }
}
