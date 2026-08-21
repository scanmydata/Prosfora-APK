package gr.prosfora.app.notify

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.telephony.SmsManager
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.UUID
import kotlin.coroutines.resume

/**
 * Αποστολή SMS **με πραγματική επιβεβαίωση**.
 *
 * Το άνοιγμα της εφαρμογής μηνυμάτων με έτοιμο κείμενο δεν λέει τίποτα: ο
 * χρήστης μπορεί να γυρίσει πίσω χωρίς να πατήσει αποστολή. Εδώ το μήνυμα
 * φεύγει από το ίδιο το app και ο τηλεπικοινωνιακός πάροχος επιστρέφει
 * αποτέλεσμα μέσω PendingIntent — μόνο τότε καταγράφεται ως σταλμένο.
 */
object SmsSender {

    private const val ACTION_SENT = "gr.prosfora.app.SMS_SENT"

    class SmsException(message: String) : Exception(message)

    suspend fun send(context: Context, phone: String, text: String): Result<Unit> =
        suspendCancellableCoroutine { continuation ->
            val app = context.applicationContext
            val manager = smsManager(app)
            val parts = manager.divideMessage(text)
            val token = UUID.randomUUID().toString()
            val action = "$ACTION_SENT.$token"

            var remaining = parts.size
            var failure: String? = null

            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (failure == null && resultCode != Activity.RESULT_OK) {
                        failure = describe(resultCode)
                    }
                    remaining--
                    if (remaining > 0) return

                    runCatching { app.unregisterReceiver(this) }
                    val error = failure
                    if (continuation.isActive) {
                        continuation.resume(
                            if (error == null) Result.success(Unit)
                            else Result.failure(SmsException(error)),
                        )
                    }
                }
            }

            val filter = IntentFilter(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                app.registerReceiver(receiver, filter)
            }

            continuation.invokeOnCancellation { runCatching { app.unregisterReceiver(receiver) } }

            val intents = ArrayList<PendingIntent>(parts.size)
            repeat(parts.size) { index ->
                intents.add(
                    PendingIntent.getBroadcast(
                        app,
                        index,
                        Intent(action).setPackage(app.packageName),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }

            runCatching {
                manager.sendMultipartTextMessage(phone, null, parts, intents, null)
            }.onFailure { error ->
                runCatching { app.unregisterReceiver(receiver) }
                if (continuation.isActive) continuation.resume(Result.failure(error))
            }
        }

    @Suppress("DEPRECATION")
    private fun smsManager(context: Context): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }

    private fun describe(code: Int): String = when (code) {
        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Γενικό σφάλμα δικτύου"
        SmsManager.RESULT_ERROR_NO_SERVICE -> "Δεν υπάρχει σήμα"
        SmsManager.RESULT_ERROR_NULL_PDU -> "Σφάλμα μηνύματος"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "Το ραδιόφωνο του κινητού είναι κλειστό"
        else -> "Η αποστολή απέτυχε (κωδικός $code)"
    }
}
