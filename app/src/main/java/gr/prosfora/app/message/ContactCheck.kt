package gr.prosfora.app.message

/**
 * Έλεγχος email και κινητού **πριν** φύγει το μήνυμα.
 *
 * Μια προσφορά που στέλνεται σε λάθος διεύθυνση δεν γυρίζει πίσω με μήνυμα
 * λάθους — απλώς δεν φτάνει ποτέ, και το φαίνεται ως «στάλθηκε». Τα δύο λάθη
 * που συμβαίνουν στην πράξη είναι το ελληνικό πληκτρολόγιο (`γmail.com`, όπου
 * το γράμμα μοιάζει σωστό αλλά δεν είναι) και το σταθερό τηλέφωνο στη θέση του
 * κινητού, που δεν δέχεται ούτε SMS ούτε Viber.
 *
 * Ο έλεγχος είναι **συμβουλευτικός για το κενό** και αυστηρός για το λάθος: ένα
 * άδειο πεδίο δεν είναι σφάλμα —η προσφορά μπορεί να μην έχει ακόμη στοιχεία—
 * αλλά ό,τι έχει γραφτεί πρέπει να στέκει.
 */
object ContactCheck {

    /**
     * @param problem τι φταίει, στη γλώσσα του χρήστη· `null` όταν στέκει.
     * @param normalized η μορφή που αξίζει να σταλεί: χωρίς κενά, με τα
     * ψηφία μόνο για το τηλέφωνο. Κενή όταν δεν βγαίνει νόημα.
     */
    data class Verdict(val problem: String? = null, val normalized: String = "") {
        val ok: Boolean get() = problem == null
    }

    private val OK = Verdict()

    /** Γράμματα που μοιάζουν λατινικά αλλά δεν είναι — το πιο ύπουλο λάθος. */
    private val GREEK = Regex("""[Ͱ-Ͽἀ-῿]""")

    private val EMAIL = Regex("""^[A-Za-z0-9!#$%&'*+/=?^_`{|}~.-]+@[A-Za-z0-9.-]+$""")

    /** Ό,τι γράφει κανείς ανάμεσα στα ψηφία ενός τηλεφώνου και δεν φταίει. */
    private val ALLOWED_SEPARATORS = setOf(' ', '-', '.', '(', ')', '/', '+')

    fun email(raw: String): Verdict {
        val value = raw.trim()
        if (value.isEmpty()) return OK
        if (value.any { it.isWhitespace() }) return Verdict("Το email δεν μπορεί να έχει κενά")
        if (GREEK.containsMatchIn(value)) {
            return Verdict("Υπάρχουν ελληνικοί χαρακτήρες — γράψε το με λατινικά")
        }
        val at = value.count { it == '@' }
        if (at == 0) return Verdict("Λείπει το @")
        if (at > 1) return Verdict("Περισσότερα από ένα @")
        if (!EMAIL.matches(value)) return Verdict("Υπάρχει χαρακτήρας που δεν επιτρέπεται")

        val local = value.substringBefore('@')
        val domain = value.substringAfter('@')
        if (local.isEmpty()) return Verdict("Λείπει το όνομα πριν το @")
        if (local.startsWith('.') || local.endsWith('.') || local.contains("..")) {
            return Verdict("Λάθος τελείες πριν το @")
        }
        val labels = domain.split('.')
        if (labels.size < 2) return Verdict("Λείπει η κατάληξη, π.χ. .gr ή .com")
        if (labels.any { it.isEmpty() }) return Verdict("Λάθος τελείες στο domain")
        if (labels.any { it.startsWith('-') || it.endsWith('-') }) return Verdict("Λάθος παύλες στο domain")
        val tld = labels.last()
        if (tld.length < 2 || !tld.all { it.isLetter() }) {
            return Verdict("Λάθος κατάληξη «.$tld»")
        }
        return Verdict(normalized = value)
    }

    /**
     * Ελληνικό κινητό: δέκα ψηφία που αρχίζουν από 69, με ή χωρίς +30.
     *
     * Ξένοι αριθμοί περνούν σε διεθνή μορφή (`+…`): η εφαρμογή δεν ξέρει τους
     * κανόνες κάθε χώρας και δεν έχει λόγο να μπλοκάρει κάτι που ο χρήστης
     * ξέρει ότι ισχύει.
     */
    fun mobile(raw: String): Verdict {
        val value = raw.trim()
        if (value.isEmpty()) return OK
        if (value.any { !it.isDigit() && it !in ALLOWED_SEPARATORS }) {
            return Verdict("Ο αριθμός δέχεται μόνο ψηφία")
        }

        val compact = value.filter { it.isDigit() || it == '+' }
        val foreign = compact.startsWith("+") && !compact.startsWith("+30")
        if (foreign) {
            val digits = compact.drop(1)
            if (digits.length !in 8..15 || digits.isEmpty()) return Verdict("Λάθος διεθνής αριθμός")
            return Verdict(normalized = compact)
        }

        val digits = compact
            .removePrefix("+30")
            .removePrefix("0030")
            .let { if (it.startsWith("30") && it.length == 12) it.drop(2) else it }
            .filter { it.isDigit() }

        return when {
            digits.length < 10 -> Verdict("Λείπουν ψηφία — το κινητό έχει 10")
            digits.length > 10 -> Verdict("Περισσεύουν ψηφία — το κινητό έχει 10")
            digits.startsWith("69") -> Verdict(normalized = digits)
            digits.startsWith("2") -> Verdict("Σταθερό τηλέφωνο — δεν δέχεται SMS ή Viber")
            else -> Verdict("Το κινητό αρχίζει από 69")
        }
    }
}
