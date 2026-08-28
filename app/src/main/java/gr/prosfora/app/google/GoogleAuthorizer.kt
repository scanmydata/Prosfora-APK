package gr.prosfora.app.google

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.compose.*
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
                    pending = continuation
                    launchConsent(
                        IntentSenderRequest.Builder(resolution.intentSender).build(),
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

    private fun rememberAccount(result: AuthorizationResult) {
        val settings = GoogleSettings(context)
        settings.googleConnected = true
        runCatching { result.toGoogleSignInAccount()?.email }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { settings.ownerEmail = it }
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
                continuation.resumeWithException(IllegalStateException("Η σύνδεση ακυρώθηκε"))
            } else {
                continuation.resume(token)
            }
        }.onFailure { continuation.resumeWithException(it) }
    }

    companion object {
        /** Πλήρης διαχείριση Drive: ανάγνωση, μετακίνηση και διαγραφή αρχείων. */
        const val DRIVE = "https://www.googleapis.com/auth/drive"

        /** Διατηρείται για συμβατότητα με παλιό κώδικα που αναφέρεται στο όνομα. */
        const val DRIVE_FILE = "https://www.googleapis.com/auth/drive.file"

        const val SPREADSHEETS = "https://www.googleapis.com/auth/spreadsheets"
        const val GMAIL_SEND = "https://www.googleapis.com/auth/gmail.send"
        val EMAIL = Scopes.EMAIL

        val SCOPES = listOf(
            DRIVE,
            SPREADSHEETS,
            GMAIL_SEND,
            EMAIL,
        )
    }
}

@Composable
fun rememberGoogleAuthorizer(): GoogleAuthorizer {
    val context = LocalContext.current
    val holder = remember { arrayOfNulls<GoogleAuthorizer>(1) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        holder[0]?.onConsentResult(result)
    }

    return remember(context) {
        GoogleAuthorizer(context) { request -> launcher.launch(request) }
            .also { holder[0] = it }
    }
}
