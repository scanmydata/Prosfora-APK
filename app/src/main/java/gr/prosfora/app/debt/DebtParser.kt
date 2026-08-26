package gr.prosfora.app.debt

import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.DebtKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Διαβάζει τα παραστατικά των οφειλών και βγάζει γραμμές για τη βάση.
 *
 * Το κείμενο έρχεται από το Drive: το PDF αντιγράφεται ως Google Doc και
 * εξάγεται σε σκέτο κείμενο. Όσα PDF έχουν επίπεδο κειμένου διαβάζονται
 * αυτούσια· όσα είναι σχεδιασμένα σε καμπύλες — το σημείωμα πληρωμής της ΑΑΔΕ
 * είναι τέτοιο — περνάνε από το OCR του Drive.
 *
 * **Γιατί δεν ψάχνουμε συντεταγμένες**: η σειρά των γραμμών αλλάζει ανάλογα με
 * το πώς βγήκε το κείμενο, και στα ίδια τα παραστατικά κάποιοι αριθμοί
 * τυπώνονται *πριν* από την ετικέτα τους. Όλα τα μοτίβα εδώ αγκυρώνονται σε
 * ετικέτα και δέχονται οποιοδήποτε κενό ή αλλαγή γραμμής ως το νούμερο.
 *
 * Ό,τι βρεθεί περνάει πάντα από επιβεβαίωση του χρήστη πριν αποθηκευτεί.
 *
 * Reference implementation & έλεγχος σε πραγματικά αρχεία:
 * `migration/parse_debts.py`.
 */
object DebtParser {

    /**
     * Ένα ποσό όπως γράφεται στα ελληνικά έντυπα: 1.296,83.
     *
     * Τα δύο δεκαδικά με κόμμα είναι που το ξεχωρίζουν από ημερομηνία ή αριθμό
     * πρωτοκόλλου — χωρίς αυτό, το «Ποσό δόσης δήλωσης της 31/08/2026 30,00 €»
     * θα διαβαζόταν ως 31 ευρώ.
     */
    private const val AMOUNT = """([0-9][0-9.]*,[0-9]{2})"""

    /** Πόσο κείμενο επιτρέπεται ανάμεσα στην ετικέτα και στο ποσό της. */
    private const val GAP = """[\s\S]{0,60}?"""

    private val DATE = Regex("""(\d{1,2})/(\d{1,2})/(\d{4})""")
    private val PERIOD_SLASH = Regex("""(\d{1,2})\s*/\s*(\d{4})""")

    private val MONTHS = listOf(
        "ΙΑΝΟΥΑΡ", "ΦΕΒΡΟΥΑΡ", "ΜΑΡΤ", "ΑΠΡΙΛ", "ΜΑΙ", "ΙΟΥΝ",
        "ΙΟΥΛ", "ΑΥΓΟΥΣΤ", "ΣΕΠΤΕΜΒΡ", "ΟΚΤΩΒΡ", "ΝΟΕΜΒΡ", "ΔΕΚΕΜΒΡ",
    )

    /** Ο μήνας αναφοράς και το έτος μιας οφειλής. */
    data class Period(val month: Int, val year: Int) {
        companion object {
            val NONE = Period(0, 0)
        }
    }

    /**
     * Οι γραμμές που βρέθηκαν στο [text]. Κενή λίστα σημαίνει ότι το έντυπο δεν
     * αναγνωρίστηκε — τότε ο χρήστης το γράφει με το χέρι.
     */
    fun parse(text: String, fileName: String = "", driveFileId: String = ""): List<DebtEntity> {
        val clean = text.replace(' ', ' ')
        return when {
            clean.contains("Μισθοδοτική Κατάσταση", true) -> payroll(clean, fileName, driveFileId)
            clean.contains("Ταυτότητα Οφειλής", true) ||
                clean.contains("Σημείωμα για Πληρωμή", true) -> aade(clean, fileName, driveFileId)
            clean.contains("Διαφήμισης", true) ||
                clean.contains("ΔΗΜΟΣΙΟΓΡΑΦΙΚΟΣ", true) -> advertising(clean, fileName, driveFileId)
            clean.contains("ΑΠΔ", true) ||
                clean.contains("ΑΠΟΔΕΙΚΤΙΚΟΥ ΥΠΟΒΟΛΗΣ", true) -> apd(clean, fileName, driveFileId)
            else -> emptyList()
        }
    }

    // ---------------------------------------------------------------- ΑΠΔ ---

    /**
     * ΑΠΔ του ΕΦΚΑ: το ίδιο έντυπο βγαίνει δύο φορές, μία για την κύρια
     * ασφάλιση και μία για το ΤΕΚΑ. Ξεχωρίζουν από τη λέξη ΤΕΚΑ και έχουν
     * χωριστό RF το καθένα, οπότε πληρώνονται χωριστά.
     */
    private fun apd(text: String, fileName: String, driveFileId: String): List<DebtEntity> {
        val teka = text.contains("ΤΕΚΑ", true) || fileName.contains("ΤΕΚΑ", true)
        val kind = if (teka) DebtKind.TEKA else DebtKind.IKA

        val period = periodOf(
            Regex("""Περίοδος\s*(?:Από|Έως)?\s*:?\s*(\d{1,2})\s*/\s*(\d{4})""").find(text),
        ) ?: fromFileName(fileName)

        // Το «Σύνολο Εισφορών» γράφεται σε κάποια έντυπα με λατινικό o
        val amount = amountAfter(text, """Σύνολο\s*Εισφ\S*""")
            ?: amountAfter(text, """Καταβλητέες\s*Εισφορές""")
            ?: return emptyList()

        val submission = Regex("""Αριθμ?\.?\s*Υποβολής\s*:?\s*(\d+)""").find(text)?.groupValues?.get(1)

        return listOf(
            debt(
                kind = kind,
                period = period,
                amount = amount,
                reference = rfCode(text),
                description = buildString {
                    append(if (teka) "ΑΠΔ ΤΕΚΑ" else "ΑΠΔ ΙΚΑ")
                    if (submission != null) append(" · υποβολή $submission")
                },
                fileName = fileName,
                driveFileId = driveFileId,
            ),
        )
    }

    // -------------------------------------------------------------- ΑΑΔΕ ---

    /**
     * Σημείωμα πληρωμής της ΑΑΔΕ. Το ίδιο έντυπο βγαίνει για κάθε βεβαιωμένη
     * οφειλή εκτός ρύθμισης, οπότε ένας κανόνας τις καλύπτει όλες.
     */
    private fun aade(text: String, fileName: String, driveFileId: String): List<DebtEntity> {
        // Η ταυτότητα τυπώνεται σε τριάδες με κενά· ζητούμενο είναι μία σειρά
        val identity = Regex("""Ταυτότητα\s*Οφειλής\s*:?\s*([0-9][0-9\s]{18,45})""")
            .find(text)
            ?.groupValues?.get(1)
            ?.filter { it.isDigit() }
            .orEmpty()

        val amount = amountAfter(text, """Ποσό\s*δόσης""")
            ?: amountAfter(text, """Συνολικό\s*ποσό\s*οφειλής""")
            ?: return emptyList()

        // Η προθεσμία γράφεται δύο φορές: στην ετικέτα της δόσης και στο κείμενο
        val due = Regex("""Ποσό\s*δόσης\s*δήλωσης\s*της\s*(\d{1,2}/\d{1,2}/\d{4})""").find(text)
            ?.groupValues?.get(1)
            ?: Regex("""μέχρι\s*τις\s*(\d{1,2}/\d{1,2}/\d{4})""").find(text)?.groupValues?.get(1)

        val range = Regex(
            """Ημερολογιακή\s*Περίοδος\s*:?\s*(\d{1,2})/(\d{1,2})/(\d{4})""",
        ).find(text)
        val period = if (range != null) {
            Period(range.groupValues[2].toInt(), range.groupValues[3].toInt())
        } else {
            fromFileName(fileName)
        }

        val tax = Regex("""Είδος\s*Φόρου\s*:?\s*(.+)""").find(text)
            ?.groupValues?.get(1)
            ?.trim()
            ?.take(80)

        return listOf(
            debt(
                kind = DebtKind.AADE,
                period = period,
                amount = amount,
                reference = identity,
                description = tax ?: "Βεβαιωμένη οφειλή εκτός ρύθμισης",
                dueDay = due?.let { parseDate(it) },
                fileName = fileName,
                driveFileId = driveFileId,
            ),
        )
    }

    // ----------------------------------------------- διαφημιστικά τέλη ---

    private fun advertising(text: String, fileName: String, driveFileId: String): List<DebtEntity> {
        // «Ποσοστό Εισφορών 2,00» μοιάζει με το ποσό — το € το ξεχωρίζει
        val amount = amountAfter(text, """Εισφορές\s*€""")
            ?: amountAfter(text, """Εισφορές""")
            ?: return emptyList()

        val period = periodOf(
            Regex("""Περίοδος\s*:?\s*(\d{1,2})\s*/\s*(\d{4})""").find(text),
        ) ?: fromFileName(fileName)

        val cost = amountAfter(text, """Κόστος\s*Διαφήμισης\s*€?""")

        return listOf(
            debt(
                kind = DebtKind.ADVERTISING,
                period = period,
                amount = amount,
                reference = rfCode(text),
                description = buildString {
                    append("Εισφορές διαφήμισης")
                    if (cost != null) append(" · κόστος %.2f €".format(cost))
                },
                fileName = fileName,
                driveFileId = driveFileId,
            ),
        )
    }

    // --------------------------------------------------------- μισθοδοσία ---

    /**
     * Μισθοδοτική κατάσταση: μία γραμμή ανά εργαζόμενο.
     *
     * Η κατάσταση είναι πίνακας σε στήλες σταθερού πλάτους. Ο εργαζόμενος
     * ανοίγει με «α/α κωδικός ΕΠΩΝΥΜΟ ΟΝΟΜΑ …» και κλείνει με μια γραμμή που
     * είναι **μόνο αριθμοί** — τα σύνολά του. Το τελευταίο νούμερο εκείνης της
     * γραμμής είναι το πληρωτέο, δηλαδή αυτό που ζητείται.
     *
     * Η γραμμή του γενικού συνόλου δεν μπερδεύεται: περιέχει και το όνομα του
     * έργου, οπότε δεν είναι «μόνο αριθμοί».
     */
    private fun payroll(text: String, fileName: String, driveFileId: String): List<DebtEntity> {
        val period = periodOf(
            Regex("""Μισθοδοτική\s*Κατάσταση\s*(\d{1,2})\s*/\s*(\d{4})""").find(text),
        ) ?: fromFileName(fileName)

        val header = Regex("""^\s*(\d{1,3})\s+([A-Za-z0-9]{2,6})\s+(\S.*)$""")
        val lines = text.lines()

        data class Person(val code: String, val name: String, var amount: Double?)

        val people = mutableListOf<Person>()
        lines.forEach { line ->
            val match = header.find(line)
            if (match != null && looksLikeName(match.groupValues[3])) {
                val parts = match.groupValues[3].split(Regex("""\s{2,}|\t"""))
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                val name = parts.take(2).joinToString(" ").trim()
                people += Person(match.groupValues[2], name, null)
                return@forEach
            }
            val numbers = numbersOnly(line)
            // Οι ενδιάμεσες γραμμές αποδοχών έχουν λιγότερες στήλες· τα σύνολα
            // του εργαζόμενου είναι η πλατύτερη γραμμή του μπλοκ του
            if (numbers != null && numbers.size >= 8) {
                people.lastOrNull()?.amount = numbers.last()
            }
        }

        return people
            .filter { it.name.isNotBlank() && (it.amount ?: 0.0) > 0.0 }
            .map { person ->
                debt(
                    kind = DebtKind.PAYROLL,
                    period = period,
                    amount = person.amount ?: 0.0,
                    reference = "",
                    description = "Πληρωτέο μισθοδοσίας",
                    personName = person.name,
                    personCode = person.code,
                    fileName = fileName,
                    driveFileId = driveFileId,
                )
            }
    }

    /** Το υπόλοιπο της γραμμής ξεκινάει με επώνυμο, όχι με νούμερο ή σύμβολο. */
    private fun looksLikeName(rest: String): Boolean =
        rest.firstOrNull()?.isLetter() == true

    /**
     * Τα νούμερα μιας γραμμής που δεν έχει τίποτε άλλο πάνω της.
     * null σημαίνει ότι υπήρχαν και γράμματα, οπότε δεν είναι γραμμή συνόλων.
     */
    private fun numbersOnly(line: String): List<Double>? {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.any { it.isLetter() }) return null
        val tokens = trimmed.split(Regex("""\s+""")).mapNotNull { money(it) }
        return tokens.ifEmpty { null }
    }

    // ------------------------------------------------------------ κοινά ---

    private fun debt(
        kind: DebtKind,
        period: Period,
        amount: Double,
        reference: String,
        description: String,
        personName: String = "",
        personCode: String = "",
        dueDay: Long? = null,
        fileName: String,
        driveFileId: String,
    ): DebtEntity = DebtEntity(
        id = DebtEntity.idFor(kind, period.year, period.month, reference, personName),
        kind = kind,
        periodMonth = period.month,
        periodYear = period.year,
        dueDay = dueDay ?: DebtEntity.defaultDue(kind, period.year, period.month),
        amount = amount,
        reference = reference,
        description = description,
        personName = personName,
        personCode = personCode,
        source = fileName,
        driveFileId = driveFileId,
    )

    private fun periodOf(match: MatchResult?): Period? {
        if (match == null) return null
        val month = match.groupValues[1].toIntOrNull() ?: return null
        val year = match.groupValues[2].toIntOrNull() ?: return null
        return if (month in 1..12) Period(month, year) else null
    }

    /**
     * Τελευταία λύση: ο μήνας από το όνομα του αρχείου. Τα παραστατικά
     * κατεβαίνουν συνήθως ως «… 2026 ΙΟΥΛΙΟΣ.pdf».
     */
    private fun fromFileName(fileName: String): Period {
        val upper = fileName.uppercase()
        val year = Regex("""(20\d{2})""").find(upper)?.groupValues?.get(1)?.toIntOrNull()
            ?: return Period.NONE
        val month = MONTHS.indexOfFirst { upper.contains(it) } + 1
        return Period(month.coerceAtLeast(0), year)
    }

    /** Το πρώτο ποσό μετά από την ετικέτα [label]. */
    private fun amountAfter(text: String, label: String): Double? {
        val match = Regex(label + GAP + AMOUNT).find(text) ?: return null
        return money(match.groupValues.last())
    }

    /**
     * Ο κωδικός πληρωμής RF. Στα έντυπα του ΕΦΚΑ τυπώνεται σπασμένος σε ομάδες
     * («RF23902018 0 000093204 27216») — εδώ ενώνεται, γιατί έτσι μπαίνει και
     * στην τράπεζα.
     */
    private fun rfCode(text: String): String {
        val match = Regex("""RF\d{2}[0-9A-Z ]{10,40}""").find(text) ?: return ""
        return match.value.filter { !it.isWhitespace() }.take(25)
    }

    /** «1.296,83» → 1296.83. Η τελεία είναι χιλιάδες, το κόμμα υποδιαστολή. */
    private fun money(raw: String): Double? {
        val token = raw.trim().trim('€', '.', ':').replace(" ", "")
        if (token.isEmpty() || token.none { it.isDigit() }) return null
        val normalized = if (token.contains(',')) {
            token.replace(".", "").replace(',', '.')
        } else {
            token
        }
        return normalized.toDoubleOrNull()
    }

    private fun parseDate(raw: String): Long? = runCatching {
        LocalDate.parse(raw, DateTimeFormatter.ofPattern("d/M/uuuu")).toEpochDay()
    }.getOrNull()

    /** Χρησιμοποιείται από τη φόρμα χειροκίνητης καταχώρησης. */
    fun parseDay(raw: String): Long? = DATE.find(raw)?.let { parseDate(it.value) }

    /** «7/2026» από ελεύθερο κείμενο, για τη φόρμα. */
    fun parsePeriod(raw: String): Period? = periodOf(PERIOD_SLASH.find(raw))
}
