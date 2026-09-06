package gr.prosfora.app.notify

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.URLEncoder

/** Τα κανάλια επικοινωνίας με τον πελάτη, πέρα από το email. */
enum class Channel(val label: String, val storedValue: String) {
    SMS("Μήνυμα SMS", "SMS"),
    VIBER("Viber", "VIBER"),

    /**
     * Το ίδιο κανάλι, αλλά με την προσφορά συνημμένη και το κείμενο του email.
     *
     * Καταγράφεται ως «VIBER»: για την προσφορά μετράει από πού ειδοποιήθηκε ο
     * πελάτης, όχι αν κουβαλούσε αρχείο το μήνυμα.
     */
    VIBER_PDF("Viber με το PDF", "VIBER"),
    ;

    val carriesPdf: Boolean get() = this == VIBER_PDF
}

object ContactNotifier {

    private const val VIBER_PACKAGE = "com.viber.voip"
    private const val PDF_MIME = "application/pdf"

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

    /**
     * Ανοίγει το Viber με το PDF της προσφοράς συνημμένο.
     *
     * Το `viber://forward` στέλνει μόνο κείμενο, οπότε εδώ χρησιμοποιείται το
     * κανονικό μοίρασμα του Android με προορισμό το Viber. Το Viber κρατάει το
     * αρχείο αλλά συχνά αγνοεί το κείμενο που το συνοδεύει — γι' αυτό το
     * κείμενο μπαίνει **και** στο πρόχειρο, έτοιμο για επικόλληση.
     */
    fun sendPdfViaViber(context: Context, pdf: File, text: String): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", pdf)
        }.getOrNull() ?: return false

        copyToClipboard(context, text)

        val intent = Intent(Intent.ACTION_SEND)
            .setType(PDF_MIME)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, text)
            .setPackage(VIBER_PACKAGE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }

    fun copyToClipboard(context: Context, text: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Προσφορά", text))
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
