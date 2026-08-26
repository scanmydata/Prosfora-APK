package gr.prosfora.app.debt

import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.DebtKind
import gr.prosfora.app.util.asMoney
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Διαβάζει τα παραστατικά των οφειλών και βγάζει γραμμές για τη βάση.
 *
 * Το κείμενο έρχεται είτε αυτούσιο από το αρχείο (όταν έχει επίπεδο κειμένου)
 * είτε από OCR — βλ. [DocumentText]. Και στις δύο περιπτώσεις η διάταξη δεν
 * είναι εγγυημένη.
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
     * Τα δύο δεκαδικά με κόμμα είναι που το ξεχωρίζουν από ημερομηνία, ώρες
     * εργασίας ή αριθμό πρωτοκόλλου — χωρίς αυτό, το «Ποσό δόσης δήλωσης της
     * 31/08/2026 30,00 €» θα διαβαζόταν ως 31 ευρώ.
     */
    private const val AMOUNT = """([0-9][0-9.]*,[0-9]{2})"""

    /** Πόσο κείμενο επιτρέπεται ανάμεσα στην ετικέτα και στο ποσό της. */
    private const val GAP = """[\s\S]{0,60}?"""

    private val ANY_AMOUNT = Regex("""[0-9][0-9.]*,[0-9]{2}""")
    private val DATE = Regex("""(\d{1,2})/(\d{1,2})/(\d{4})""")
    private val PERIOD_SLASH = Regex("""(\d{1,2})\s*/\s*(\d{4})""")

    private val MONTHS = listOf(
        "ΙΑΝΟΥΑΡ", "ΦΕΒΡΟΥΑΡ", "ΜΑΡΤ", "ΑΠΡΙΛ", "ΜΑΙ", "ΙΟΥΝ",
        "ΙΟΥΛ", "ΑΥΓΟΥΣΤ", "ΣΕΠΤΕΜΒΡ", "ΟΚΤΩΒΡ", "ΝΟΕΜΒΡ", "ΔΕΚΕΜΒΡ",
    )

    /** Οι κωδικοί αποδοχών της μισθοδοτικής κατάστασης, όπως τυπώνονται. */
    private val PAY_TYPES = mapOf(
        "ΤΑ" to "Τακτικές αποδοχές",
        "ΕΑ" to "Επίδομα αδείας",
        "ΑΛ" to "Άδεια ληφθείσα",
        "ΜΛ" to "Άδεια μη ληφθείσα",
        "ΔΠ" to "Δώρο Πάσχα",
        "ΔΧ" to "Δώρο Χριστουγέννων",
    )

    /** Τα δώρα πληρώνονται χωριστά, οπότε γίνονται δική τους γραμμή. */
    private val BONUS_TYPES = setOf("ΔΠ", "ΔΧ")

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
        val clean = text.replace(' ', ' ')
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

        return listOf(
            debt(
                kind = DebtKind.AADE,
                period = period,
                amount = amount,
                reference = debtIdentity(text),
                description = taxKind(text) ?: "Βεβαιωμένη οφειλή εκτός ρύθμισης",
                dueDay = due?.let { parseDate(it) },
                fileName = fileName,
                driveFileId = driveFileId,
            ),
        )
    }

    /**
     * Η «Ταυτότητα Οφειλής», ως μία συνεχόμενη σειρά ψηφίων.
     *
     * Τυπώνεται σε τριάδες με κενά και το OCR μπορεί να βάλει τελείες ή παύλες
     * ανάμεσα. Αν η ετικέτα δεν βρεθεί καθόλου, ψάχνεται σκέτη μια αρκετά
     * μεγάλη ομάδα ψηφίων: τίποτε άλλο στο έντυπο δεν έχει τόσα.
     */
    internal fun debtIdentity(text: String): String {
        Regex("""Ταυτότητα\s*Οφειλής\s*:?\s*([0-9][0-9\s.\-]{18,50})""").find(text)
            ?.groupValues?.get(1)
            ?.filter { it.isDigit() }
            ?.takeIf { it.length >= 15 }
            ?.let { return it }

        return Regex("""(?<![0-9])([0-9][0-9\s.\-]{22,45}[0-9])(?![0-9])""").findAll(text)
            .map { it.groupValues[1].filter(Char::isDigit) }
            .firstOrNull { it.length in 20..32 }
            .orEmpty()
    }

    /**
     * Το «Είδος Φόρου», που σπάει σε δύο γραμμές στο έντυπο.
     *
     * Διαβάζεται ως ό,τι υπάρχει μέχρι την **επόμενη ετικέτα** και όχι μέχρι το
     * τέλος της γραμμής, αλλιώς έμενε μισό — «ΑΜΟΙΒΕΣ ΑΠΟ» χωρίς τη συνέχεια.
     */
    internal fun taxKind(text: String): String? {
        val next = "Ημερολογιακή|Συνολικό|Ποσό|Ταυτότητα|Ημ/νία|Προσοχή|ΔΟΥ|Τύπος"
        val match = Regex("""Είδος\s*Φόρου\s*:?\s*([\s\S]{1,140}?)\s*(?=$next|$)""").find(text)
            ?: return null
        return match.groupValues[1]
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trimEnd(',', '.', ':')
            .take(90)
            .ifBlank { null }
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
                    if (cost != null) append(" · κόστος ${cost.asMoney()}")
                },
                fileName = fileName,
                driveFileId = driveFileId,
            ),
        )
    }

    // --------------------------------------------------------- μισθοδοσία ---

    private data class Detail(val code: String, val gross: Double)

    private class Person(val code: String, val name: String) {
        val details = mutableListOf<Detail>()
        var payable: Double? = null
    }

    /**
     * Μισθοδοτική κατάσταση: μία γραμμή ανά εργαζόμενο, με τα δώρα χωριστά.
     *
     * Η κατάσταση είναι πίνακας σε στήλες. Ο εργαζόμενος ανοίγει με
     * «α/α κωδικός ΕΠΩΝΥΜΟ ΟΝΟΜΑ …», ακολουθούν γραμμές αποδοχών που ξεκινούν
     * με τον κωδικό τους (ΤΑ, ΕΑ, ΑΛ, ΜΛ, ΔΠ, ΔΧ), και κλείνει με μια γραμμή
     * που είναι **μόνο αριθμοί** — τα σύνολά του. Το τελευταίο νούμερο εκείνης
     * της γραμμής είναι το πληρωτέο.
     *
     * Η γραμμή του γενικού συνόλου δεν μπερδεύεται: περιέχει και το όνομα του
     * έργου, οπότε δεν είναι «μόνο αριθμοί».
     */
    private fun payroll(text: String, fileName: String, driveFileId: String): List<DebtEntity> {
        val period = periodOf(
            Regex("""Μισθοδοτική\s*Κατάσταση\s*(\d{1,2})\s*/\s*(\d{4})""").find(text),
        ) ?: fromFileName(fileName)

        val people = readPeople(text)
        val rows = people.flatMap { rowsFor(it, period, fileName, driveFileId) }
        return merge(rows)
    }

    private fun readPeople(text: String): List<Person> {
        val header = Regex("""^\s*(\d{1,3})\s+([A-Za-z0-9]{2,6})\s+(\S.*)$""")
        val detail = Regex("""^\s*([Α-ΩΆΈΉΊΌΎΏ]{2})\s+\S""")
        val people = mutableListOf<Person>()

        text.lines().forEach { line ->
            val head = header.find(line)
            if (head != null && head.groupValues[3].firstOrNull()?.isLetter() == true) {
                people += Person(head.groupValues[2], nameOf(head.groupValues[3]))
                return@forEach
            }

            val person = people.lastOrNull() ?: return@forEach

            detail.find(line)?.let { match ->
                val gross = ANY_AMOUNT.find(line)?.value?.let(::money)
                if (gross != null && gross > 0.0) {
                    person.details += Detail(match.groupValues[1], gross)
                }
                return@forEach
            }

            // Οι ενδιάμεσες γραμμές αποδοχών έχουν λιγότερες στήλες· τα σύνολα
            // του εργαζόμενου είναι η πλατύτερη γραμμή του μπλοκ του
            val numbers = numbersOnly(line)
            if (numbers != null && numbers.size >= 8) person.payable = numbers.last()
        }
        return people
    }

    /**
     * Επώνυμο και όνομα — τα δύο πρώτα λεκτικά.
     *
     * Πάντα δύο, ανεξάρτητα από το πόσα κενά χωρίζουν τις στήλες: το κείμενο
     * από OCR χάνει τη στοίχιση, και ένα όνομα που άλλαζε ανάλογα με τον τρόπο
     * ανάγνωσης θα έφτιαχνε διπλοεγγραφές για το ίδιο άτομο.
     */
    private fun nameOf(rest: String): String =
        rest.trim().split(Regex("""\s+""")).take(2).joinToString(" ").trim()

    private fun rowsFor(
        person: Person,
        period: Period,
        fileName: String,
        driveFileId: String,
    ): List<DebtEntity> {
        val payable = person.payable ?: return emptyList()
        if (payable <= 0.0 || person.name.isBlank()) return emptyList()

        val bonuses = person.details.filter { it.code in BONUS_TYPES }
        val regular = person.details.filterNot { it.code in BONUS_TYPES }
        val totalGross = person.details.sumOf { it.gross }

        // Χωρίς δώρα δεν υπάρχει τίποτα να μοιραστεί
        if (bonuses.isEmpty() || totalGross <= 0.0) {
            return listOf(
                payrollRow(
                    DebtKind.PAYROLL, person, period, payable,
                    describe(regular), fileName, driveFileId,
                ),
            )
        }

        // Το πληρωτέο δίνεται ενιαίο για όλο τον μήνα· η κατανομή του στα δώρα
        // γίνεται αναλογικά με τις μεικτές αποδοχές, γιατί άλλο στοιχείο δεν
        // υπάρχει στο έντυπο. Είναι εκτίμηση και διορθώνεται με το χέρι.
        val rows = mutableListOf<DebtEntity>()
        var left = payable
        bonuses.forEach { bonus ->
            val share = round2(payable * bonus.gross / totalGross)
            left -= share
            rows += payrollRow(
                DebtKind.PAYROLL_BONUS, person, period, share,
                "${PAY_TYPES[bonus.code] ?: bonus.code} · μεικτά ${bonus.gross.asMoney()} " +
                    "(αναλογικά)",
                fileName, driveFileId,
            )
        }
        if (round2(left) > 0.0) {
            rows += payrollRow(
                DebtKind.PAYROLL, person, period, round2(left),
                describe(regular), fileName, driveFileId,
            )
        }
        return rows
    }

    private fun payrollRow(
        kind: DebtKind,
        person: Person,
        period: Period,
        amount: Double,
        description: String,
        fileName: String,
        driveFileId: String,
    ) = debt(
        kind = kind,
        period = period,
        amount = amount,
        reference = "",
        description = description,
        personName = person.name,
        personCode = person.code,
        fileName = fileName,
        driveFileId = driveFileId,
    )

    /** «Τακτικές αποδοχές 364,06 € · Επίδομα αδείας 120,00 €» */
    private fun describe(details: List<Detail>): String {
        if (details.isEmpty()) return "Πληρωτέο μισθοδοσίας"
        return details
            .groupBy { it.code }
            .map { (code, rows) ->
                "${PAY_TYPES[code] ?: code} ${rows.sumOf { it.gross }.asMoney()}"
            }
            .joinToString(" · ")
    }

    /**
     * Ο ίδιος εργαζόμενος δύο φορές στην ίδια κατάσταση γίνεται μία πληρωμή.
     *
     * Συμβαίνει όταν οι αποδοχές του σπάνε σε περισσότερες από μία εγγραφές.
     * Χωρίς αυτό, η δεύτερη γραμμή θα έσβηνε την πρώτη — το αναγνωριστικό
     * βγαίνει από το όνομα και την περίοδο, οπότε είναι το ίδιο.
     */
    private fun merge(rows: List<DebtEntity>): List<DebtEntity> =
        rows.groupBy { it.id }.map { (_, same) ->
            if (same.size == 1) {
                same.first()
            } else {
                same.first().copy(
                    amount = round2(same.sumOf { it.amount }),
                    description = same.map { it.description }.distinct().joinToString(" · "),
                )
            }
        }

    /** Το υπόλοιπο της γραμμής ξεκινάει με επώνυμο, όχι με νούμερο ή σύμβολο. */
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

    private fun round2(value: Double) = Math.round(value * 100.0) / 100.0

    private fun parseDate(raw: String): Long? = runCatching {
        LocalDate.parse(raw, DateTimeFormatter.ofPattern("d/M/uuuu")).toEpochDay()
    }.getOrNull()

    /** Χρησιμοποιείται από τη φόρμα χειροκίνητης καταχώρησης. */
    fun parseDay(raw: String): Long? = DATE.find(raw)?.let { parseDate(it.value) }

    /** «7/2026» από ελεύθερο κείμενο, για τη φόρμα. */
    fun parsePeriod(raw: String): Period? = periodOf(PERIOD_SLASH.find(raw))
}
