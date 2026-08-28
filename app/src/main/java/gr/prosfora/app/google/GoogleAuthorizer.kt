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
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.Scopes
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Παίρνει OAuth access token για τα Google APIs, με το consent window της Google.
 *
 * Ζητούνται:
 *
 *  * drive.file      → αρχεία που δημιουργεί/διαχειρίζεται η εφαρμογή
 *  * drive.readonly  → ανάγνωση αρχείων που ο χρήστης ανεβάζει χειροκίνητα
 *                      στους φακέλους του Drive
 *  * spreadsheets    → κοινόχρηστη βάση δεδομένων
 *  * gmail.send      → αποστολή email
 *  * email           → email του συνδεδεμένου λογαριασμού
 *
 * Το drive.readonly είναι απαραίτητο για να μπορεί η σάρωση Οφειλών να
 * βρίσκει PDF/JPG/PNG που ανέβηκαν από υπολογιστή ή από άλλο client.
 *
 * Δεν χρειάζεται client ID στον κώδικα: ο Android OAuth client ταυτοποιείται
 * από το package name + το SHA-1 της υπογραφής.
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
                    // Πρώτη φορά ή προστέθηκε νέο scope:
                    // ο χρήστης πρέπει να δώσει ξανά έγκριση.
                    pending = continuation
                    launchConsent(
                        IntentSenderRequest.Builder(
                            resolution.intentSender,
                        ).build(),
                    )
                } else {
                    rememberAccount(result)

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
            .addOnFailureListener {
                continuation.resumeWithException(it)
            }

        continuation.invokeOnCancellation {
            pending = null
        }
    }

    /**
     * Κρατάει τη διεύθυνση του λογαριασμού από το αποτέλεσμα της έγκρισης.
     */
    private fun rememberAccount(result: AuthorizationResult) {
        val settings = GoogleSettings(context)

        settings.googleConnected = true

        runCatching {
            result.toGoogleSignInAccount()?.email
        }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let {
                settings.ownerEmail = it
            }
    }

    internal fun onConsentResult(result: ActivityResult) {
        val continuation = pending ?: return
        pending = null

        runCatching {
            Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(result.data)
                .also(::rememberAccount)
                .accessToken
        }.onSuccess { token ->
            if (token.isNullOrBlank()) {
                continuation.resumeWithException(
                    IllegalStateException("Η σύνδεση ακυρώθηκε"),
                )
            } else {
                continuation.resume(token)
            }
        }.onFailure {
            continuation.resumeWithException(it)
        }
    }

    companion object {

        /**
         * Επιτρέπει στην εφαρμογή να διαχειρίζεται αρχεία που έχει δημιουργήσει
         * η ίδια.
         */
        const val DRIVE_FILE =
            "https://www.googleapis.com/auth/drive.file"

        /**
         * Επιτρέπει στην εφαρμογή να διαβάζει αρχεία που βρίσκονται στο Drive.
         *
         * Απαραίτητο ώστε η αυτόματη σάρωση των «Οφειλών» να βλέπει PDF/JPG/PNG
         * που ο χρήστης ανέβασε από αλλού.
         */
        const val DRIVE_READONLY =
            "https://www.googleapis.com/auth/drive.readonly"

        /**
         * Το κοινόχρηστο Sheet που παίζει τον ρόλο της βάσης.
         */
        const val SPREADSHEETS =
            "https://www.googleapis.com/auth/spreadsheets"

        /**
         * Αποστολή email χωρίς app password.
         */
        const val GMAIL_SEND =
            "https://www.googleapis.com/auth/gmail.send"

        /**
         * Email του συνδεδεμένου λογαριασμού.
         */
        val EMAIL = Scopes.EMAIL

        /**
         * Όλα σε ένα consent window.
         */
        val SCOPES = listOf(
            DRIVE_FILE,
            DRIVE_READONLY,
            SPREADSHEETS,
            GMAIL_SEND,
            EMAIL,
        )
    }
}

@Composable
fun rememberGoogleAuthorizer(): GoogleAuthorizer {
    val context = LocalContext.current

    // Ο launcher χρειάζεται τον authorizer και ο authorizer τον launcher.
    // Ο holder σπάει τον κύκλο και επιβιώνει των recompositions.
    val holder = remember {
        arrayOfNulls<GoogleAuthorizer>(1)
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        holder[0]?.onConsentResult(result)
    }

    return remember(context) {
        GoogleAuthorizer(context) { request ->
            launcher.launch(request)
        }.also {
            holder[0] = it
        }
    }
}
