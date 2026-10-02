package gr.prosfora.app.sync

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import gr.prosfora.app.google.GoogleAuthorizer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Access token χωρίς οθόνη, για όσα τρέχουν με την εφαρμογή κλειστή.
 *
 * Όταν η Google ζητήσει έγκριση από τον χρήστη επιστρέφει `null`: καμία
 * επανάληψη στο παρασκήνιο δεν θα τη δώσει, και η δουλειά κλείνει ήσυχα.
 */
object BackgroundToken {
    suspend fun withoutUi(context: Context): String? = suspendCancellableCoroutine { continuation ->
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
}
