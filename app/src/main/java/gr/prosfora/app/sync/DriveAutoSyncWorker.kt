package gr.prosfora.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import gr.prosfora.app.debug.DebugLog
import gr.prosfora.app.google.GoogleAuthorizer
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Background συγχρονισμός. Τρέχει ακόμη κι όταν το UI της εφαρμογής δεν είναι ανοικτό.
 * Αν έχει ήδη δοθεί η Google έγκριση, παίρνει access token χωρίς UI και ελέγχει Drive/Sheet.
 */
class DriveAutoSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        DebugLog.log("auto-sync", "έναρξη background sync")

        val token = runCatching { accessTokenWithoutUi(applicationContext) }
            .onFailure { DebugLog.log("auto-sync", "δεν πήρα token: ${it.stackTraceToString()}") }
            .getOrNull()
            ?: return Result.success()

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

    private suspend fun accessTokenWithoutUi(context: Context): String? =
        suspendCancellableCoroutine { continuation ->
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(
                    GoogleAuthorizer.SCOPES.map(::Scope),
                )
                .build()

            Identity.getAuthorizationClient(context)
                .authorize(request)
                .addOnSuccessListener { result ->
                    if (result.hasResolution()) {
                        continuation.resume(null)
                        return@addOnSuccessListener
                    }
                    val token = result.accessToken
                    if (token.isNullOrBlank()) {
                        continuation.resumeWithException(
                            IllegalStateException("Η Google δεν επέστρεψε background access token"),
                        )
                    } else {
                        continuation.resume(token)
                    }
                }
                .addOnFailureListener { continuation.resumeWithException(it) }
        }

    companion object {
        private const val WORK_NAME = "prosfora-drive-auto-sync"
        private const val INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DriveAutoSyncWorker>(
                INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            DebugLog.log("auto-sync", "προγραμματίστηκε ανά $INTERVAL_MINUTES λεπτά")
        }
    }
}
