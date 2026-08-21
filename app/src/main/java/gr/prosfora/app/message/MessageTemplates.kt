package gr.prosfora.app.message

import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.asOfferDate
import gr.prosfora.app.util.strippedKind

/**
 * Τα δυναμικά πεδία που μπορεί να βάλει ο χρήστης μέσα στα πρότυπα μηνυμάτων.
 *
 * Γράφονται στα ελληνικά μέσα σε άγκιστρα, ώστε το πρότυπο να διαβάζεται από
 * κάποιον που δεν είναι προγραμματιστής.
 */
enum class MessageField(val token: String, val label: String) {
    GREETING("{χαιρετισμός}", "Χαιρετισμός"),
    NAME("{ονοματεπώνυμο}", "Ονοματεπώνυμο"),
    ADDRESS("{διεύθυνση}", "Οδός / Περιοχή"),
    KIND("{είδος}", "Είδος"),
    DATE("{ημερομηνία}", "Ημερομηνία"),
    TOTAL("{σύνολο}", "Γενικό σύνολο"),
    EMAIL("{email}", "Email πελάτη"),
    PHONE("{κινητό}", "Κινητό πελάτη"),
    ;

    fun valueFor(details: OfferWithDetails): String {
        val offer = details.offer
        return when (this) {
            GREETING -> if (offer.customerName.isNotBlank()) {
                "Καλησπέρα ${offer.customerName.trim()},"
            } else {
                "Καλησπέρα,"
            }
            NAME -> offer.customerName
            ADDRESS -> offer.address
            KIND -> offer.kind.strippedKind().ifBlank { "κατοικία" }
            DATE -> offer.dateEpochDay.asOfferDate()
            TOTAL -> details.total.asMoney()
            EMAIL -> offer.email
            PHONE -> offer.customerPhone
        }
    }
}

object MessageTemplates {

    /** Αντικαθιστά όλα τα `{πεδία}` με τις τιμές της συγκεκριμένης προσφοράς. */
    fun render(template: String, details: OfferWithDetails): String {
        var text = template
        MessageField.entries.forEach { field ->
            text = text.replace(field.token, field.valueFor(details))
        }
        return text.trim()
    }

    const val DEFAULT_EMAIL_SUBJECT = "Προσφορά ελαιοχρωματισμών {διεύθυνση}"

    val DEFAULT_EMAIL_BODY = """
        {χαιρετισμός}

        Σας αποστέλλω την προσφορά για το χρωματισμό της {είδος} σας επί της οδού {διεύθυνση}.
        Στη διάθεσή σας για οποιαδήποτε επιπλέον πληροφορία χρειαστείτε.
    """.trimIndent()

    val DEFAULT_SMS = """
        {χαιρετισμός} σας έχω στείλει στο email σας την προσφορά ελαιοχρωματισμών για την {είδος} σας επί της οδού {διεύθυνση}.
    """.trimIndent()

    /** Το Viber δεν έχει όριο χαρακτήρων όπως το SMS, αλλά το κείμενο μένει ίδιο. */
    val DEFAULT_VIBER = DEFAULT_SMS
}
