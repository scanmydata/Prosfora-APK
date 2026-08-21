package gr.prosfora.app.google

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Παίρνει OAuth access token για τα Google APIs, με το consent window της Google.
 *
 * Ζητούνται τρία scopes σε ένα consent window: `drive.file` για το πρότυπο και
 * τα PDF, `spreadsheets` για την κοινόχρηστη βάση, και `gmail.send` για την
 * αποστολή — μόνο αποστολή, κανένα δικαίωμα ανάγνωσης αλληλογραφίας.
 *
 * Δεν χρειάζεται client ID στον κώδικα: ο Android OAuth client ταυτοποιείται
 * από το package name + το SHA-1 της υπογραφής — βλ. docs/google-cloud.md.
 */
class GoogleAuthorizer internal constructor(
    private val context: Context,
    private val launchConsent: (IntentSenderRequest) -> Unit,
) {

    private var pending: CancellableContinuation<String>? = null

    suspend fun accessToken(): String = suspendCancellableCoroutine { continuation ->
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(SCOPES.map { Scope(it) })
            .build()

        Identity.getAuthorizationClient(context)
            .authorize(request)
            .addOnSuccessListener { result ->
                val resolution = result.pendingIntent
                if (result.hasResolution() && resolution != null) {
                    // Πρώτη φορά: ο χρήστης πρέπει να δώσει έγκριση
                    pending = continuation
                    launchConsent(IntentSenderRequest.Builder(resolution.intentSender).build())
                } else {
                    val token = result.accessToken
                    if (token.isNullOrBlank()) {
                        continuation.resumeWithException(
                            IllegalStateException("Η Google δεν επέστρεψε token"),
                        )
                    } else {
                        continuation.resume(token)
                    }
                }
            }
            .addOnFailureListener { continuation.resumeWithException(it) }

        continuation.invokeOnCancellation { pending = null }
    }

    internal fun onConsentResult(result: ActivityResult) {
        val continuation = pending ?: return
        pending = null
        runCatching {
            Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(result.data)
                .accessToken
        }.onSuccess { token ->
            if (token.isNullOrBlank()) {
                continuation.resumeWithException(IllegalStateException("Η σύνδεση ακυρώθηκε"))
            } else {
                continuation.resume(token)
            }
        }.onFailure(continuation::resumeWithException)
    }

    companion object {
        /** Πρότυπο + παραγόμενα PDF. Non-sensitive: μόνο αρχεία του app. */
        const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

        /** Το κοινόχρηστο Sheet που παίζει τον ρόλο της βάσης. Sensitive. */
        const val SPREADSHEETS = "https://www.googleapis.com/auth/spreadsheets"

        /** Αποστολή email χωρίς app password. Sensitive — μόνο αποστολή, καμία ανάγνωση. */
        const val GMAIL_SEND = "https://www.googleapis.com/auth/gmail.send"

        /** Όλα σε ένα consent window — ο χρήστης εγκρίνει μία φορά. */
        val SCOPES = listOf(DRIVE_FILE, SPREADSHEETS, GMAIL_SEND)
    }
}

@Composable
fun rememberGoogleAuthorizer(): GoogleAuthorizer {
    val context = LocalContext.current
    // Ο launcher χρειάζεται τον authorizer και ο authorizer τον launcher· ο
    // holder σπάει τον κύκλο και επιβιώνει των recompositions.
    val holder = remember { arrayOfNulls<GoogleAuthorizer>(1) }
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result -> holder[0]?.onConsentResult(result) }

    return remember(context) {
        GoogleAuthorizer(context) { request -> launcher.launch(request) }
            .also { holder[0] = it }
    }
}
