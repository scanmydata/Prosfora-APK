package gr.prosfora.app.message

import gr.prosfora.app.data.db.Gender
import gr.prosfora.app.data.db.OfferWithDetails

/** Με τι προσφωνείται ο πελάτης στην αρχή του μηνύματος. */
enum class GreetingStyle(val label: String, val example: String) {
    FIRST_NAME("Με το μικρό όνομα", "Καλημέρα Μαρία,"),
    LAST_NAME("Με το επώνυμο", "Καλημέρα κυρία Παπαδοπούλου,"),
    FULL_NAME("Με ονοματεπώνυμο", "Καλημέρα Μαρία Παπαδοπούλου,"),
}

data class GreetingOptions(
    val style: GreetingStyle = GreetingStyle.FIRST_NAME,
    /** Αν μπαίνει «κύριε»/«κυρία» μπροστά από το επώνυμο. */
    val useTitle: Boolean = true,
)

/**
 * Ο χαιρετισμός στην κορυφή κάθε μηνύματος.
 *
 * «Καλημέρα» ως τις 12:00, «Καλησπέρα» μετά — με βάση την ώρα που πατιέται η
 * αποστολή, όχι την ώρα που γράφτηκε η προσφορά.
 */
object Greeting {

    fun timeOfDay(now: java.time.LocalTime = java.time.LocalTime.now()): String =
        if (now.hour < 12) "Καλημέρα" else "Καλησπέρα"

    fun forOffer(
        details: OfferWithDetails,
        options: GreetingOptions,
        now: java.time.LocalTime = java.time.LocalTime.now(),
    ): String {
        val word = timeOfDay(now)
        val name = addressee(details, options)
        return if (name.isBlank()) "$word," else "$word $name,"
    }

    private fun addressee(details: OfferWithDetails, options: GreetingOptions): String {
        val offer = details.offer
        val first = offer.customerName.trim()
        val last = offer.customerLastName.trim()

        return when (options.style) {
            GreetingStyle.FIRST_NAME -> first.ifBlank { withTitle(last, offer.customerGender, options) }
            GreetingStyle.FULL_NAME -> listOf(first, last).filter { it.isNotBlank() }.joinToString(" ")
            GreetingStyle.LAST_NAME ->
                if (last.isBlank()) first else withTitle(last, offer.customerGender, options)
        }
    }

    private fun withTitle(last: String, gender: Gender, options: GreetingOptions): String {
        if (last.isBlank()) return ""
        val title = if (options.useTitle) gender.title else ""
        val surname = if (gender == Gender.MALE) vocative(last) else last
        return listOf(title, surname).filter { it.isNotBlank() }.joinToString(" ")
    }

    /**
     * Κλητική ανδρικού επωνύμου: «Παπαδόπουλος» → «Παπαδόπουλε».
     *
     * Μόνο οι τρεις καταλήξεις που καλύπτουν σχεδόν όλα τα ελληνικά επώνυμα.
     * Όσα είναι ήδη σε γενική —Γεωργίου, Παπαδοπούλου— μένουν ως έχουν, που
     * είναι και το σωστό. Ό,τι δεν αναγνωρίζεται δεν πειράζεται: καλύτερα
     * αμετάβλητο παρά λάθος, και ο χρήστης βλέπει το κείμενο πριν σταλεί.
     */
    internal fun vocative(surname: String): String = when {
        surname.endsWith("ους", ignoreCase = true) -> surname
        surname.endsWith("ος", ignoreCase = true) -> surname.dropLast(2) + "ε"
        surname.endsWith("ης", ignoreCase = true) -> surname.dropLast(2) + "η"
        surname.endsWith("ής", ignoreCase = true) -> surname.dropLast(2) + "ή"
        surname.endsWith("ας", ignoreCase = true) -> surname.dropLast(2) + "α"
        surname.endsWith("άς", ignoreCase = true) -> surname.dropLast(2) + "ά"
        else -> surname
    }
}
