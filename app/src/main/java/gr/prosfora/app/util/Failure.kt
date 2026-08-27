package gr.prosfora.app.util

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Το γιατί απέτυχε κάτι, σε γλώσσα που καταλαβαίνει ο χρήστης.
 *
 * Η συνηθέστερη αιτία είναι η πιο βαρετή: δεν υπάρχει δίκτυο. Χωρίς αυτό ο
 * χρήστης έβλεπε «Unable to resolve host "www.googleapis.com"» ή, χειρότερα,
 * έναν σκέτο αριθμό από τις υπηρεσίες Google — και δεν είχε τρόπο να μαντέψει
 * ότι φταίει απλώς το ίντερνετ και όχι η εφαρμογή ή τα δεδομένα του.
 */
fun Throwable.reason(): String = when {
    isOffline() -> "δεν υπάρχει σύνδεση στο ίντερνετ"
    else -> message?.takeIf { it.isNotBlank() }
        ?: this::class.simpleName
        ?: "άγνωστο σφάλμα"
}

/**
 * Είναι αυτό «δεν έχω δίκτυο»;
 *
 * Ψάχνεται όλη η αλυσίδα αιτίων: το OkHttp και οι υπηρεσίες Google τυλίγουν την
 * πραγματική εξαίρεση σε δικές τους. Ο μετρητής βημάτων υπάρχει επειδή μια
 * εξαίρεση μπορεί να δείχνει τον εαυτό της ως αιτία.
 */
private fun Throwable.isOffline(): Boolean {
    var cause: Throwable? = this
    var steps = 0
    while (cause != null && steps++ < 8) {
        when (cause) {
            is UnknownHostException, is ConnectException, is SocketTimeoutException -> return true
            is ApiException ->
                if (cause.statusCode == CommonStatusCodes.NETWORK_ERROR) return true
            is IOException ->
                if (cause.message?.contains("network", ignoreCase = true) == true) return true
        }
        cause = cause.cause
    }
    return false
}
