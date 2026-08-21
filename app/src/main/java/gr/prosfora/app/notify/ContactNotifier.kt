package gr.prosfora.app.notify

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.util.strippedKind
import java.net.URLEncoder

/**
 * Ειδοποίηση του πελάτη ότι στάλθηκε η προσφορά, μέσω SMS ή Viber.
 *
 * Και τα δύο ανοίγουν την αντίστοιχη εφαρμογή με το κείμενο έτοιμο — **δεν**
 * στέλνουν μόνα τους. Ο χρήστης πατάει αποστολή, όπως θα έκανε ούτως ή άλλως,
 * και το μήνυμα φεύγει από τον δικό του αριθμό.
 */
object ContactNotifier {

    enum class Channel(val label: String, val storedValue: String) {
        SMS("Μήνυμα", "SMS"),
        VIBER("Viber", "VIBER"),
    }

    private const val VIBER_PACKAGE = "com.viber.voip"

    /**
     * «Καλησπέρα, σας έχω στείλει στο email σας την προσφορά ελαιοχρωματισμών
     * για την <είδος> σας επί της οδού <οδός/περιοχή>.»
     */
    fun message(details: OfferWithDetails): String {
        val offer = details.offer
        val greeting = if (offer.customerName.isNotBlank()) {
            "Καλησπέρα ${offer.customerName.trim()},"
        } else {
            "Καλησπέρα,"
        }
        val kind = offer.kind.strippedKind().ifBlank { "κατοικία" }
        return "$greeting σας έχω στείλει στο email σας την προσφορά " +
            "ελαιοχρωματισμών για την $kind σας επί της οδού ${offer.address}."
    }

    fun isViberInstalled(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(VIBER_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** Ανοίγει την εφαρμογή μηνυμάτων/Viber. Επιστρέφει false αν δεν βρέθηκε. */
    fun open(context: Context, channel: Channel, details: OfferWithDetails): Boolean {
        val text = message(details)
        val phone = details.offer.customerPhone.filter { it.isDigit() || it == '+' }
        val intent = when (channel) {
            Channel.SMS -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$phone")).apply {
                putExtra("sms_body", text)
            }
            // Το Viber δεν δέχεται παραλήπτη από intent· ανοίγει την επιλογή επαφής
            // με το κείμενο έτοιμο.
            Channel.VIBER -> Intent(
                Intent.ACTION_VIEW,
                Uri.parse("viber://forward?text=${URLEncoder.encode(text, "UTF-8")}"),
            ).setPackage(VIBER_PACKAGE)
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
