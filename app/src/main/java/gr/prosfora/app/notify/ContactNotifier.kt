package gr.prosfora.app.notify

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.net.URLEncoder

/** Τα κανάλια επικοινωνίας με τον πελάτη, πέρα από το email. */
enum class Channel(val label: String, val storedValue: String) {
    SMS("Μήνυμα SMS", "SMS"),
    VIBER("Viber", "VIBER"),
}

object ContactNotifier {

    private const val VIBER_PACKAGE = "com.viber.voip"

    fun isViberInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(VIBER_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /**
     * Ανοίγει το Viber με το κείμενο έτοιμο.
     *
     * Το Viber δεν προσφέρει τρόπο να μάθουμε αν ο χρήστης πάτησε τελικά
     * αποστολή — γι' αυτό ο καλών ρωτάει ρητά μετά την επιστροφή, αντί να
     * θεωρήσει ότι στάλθηκε.
     */
    fun openViber(context: Context, text: String): Boolean {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("viber://forward?text=${URLEncoder.encode(text, "UTF-8")}"),
        ).setPackage(VIBER_PACKAGE).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    /** Εφεδρικό: η εφαρμογή μηνυμάτων με προσυμπληρωμένο κείμενο. */
    fun openSmsApp(context: Context, phone: String, text: String): Boolean {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${phone.digitsAndPlus()}"))
            .putExtra("sms_body", text)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun String.digitsAndPlus(): String = filter { it.isDigit() || it == '+' }
}
