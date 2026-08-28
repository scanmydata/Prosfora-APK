package gr.prosfora.app.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
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

/** Background συγχρονισμός που εκτελείται όταν χτυπήσει ο adaptive alarm. */
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
                .setRequestedScopes(GoogleAuthorizer.SCOPES.map(::Scope))
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
        private const val WORK_NAME = "prosfora-drive-auto-sync-now"

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

        /** Συμβατότητα με το παλιό startup call. */
        fun schedule(context: Context) = DriveAutoSyncScheduler.schedule(context)
    }
}
