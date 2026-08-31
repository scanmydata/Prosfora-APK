package gr.prosfora.app.debt

import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.DebtKind
import gr.prosfora.app.debug.DebugLog
import gr.prosfora.app.debt.DebtText.anchor
import gr.prosfora.app.debt.DebtText.looksLike
import gr.prosfora.app.debt.DebtText.normalize
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
 * **Γιατί περνάει πρώτα από την [DebtText]**: οι ετικέτες δεν γράφονται ποτέ
 * κατά λέξη. Το OCR χάνει τους τόνους και μπερδεύει τα ελληνικά κεφαλαία με τα
 * λατινικά δίδυμά τους, οπότε ένα «Ποσό δόσης» γραμμένο έτσι δεν ταιριάζει με
 * τίποτα. Κείμενο και ετικέτες κανονικοποιούνται και οι δύο πριν συγκριθούν.
 *
 * Ό,τι βρεθεί περνάει πάντα από επιβεβαίωση του χρήστη πριν αποθηκευτεί.
 *
 * Reference implementation & έλεγχος σε πραγματικά αρχεία:
 * `migration/parse_debts.py`.
 */
object DebtParser {

    private const val TAG = "parser"

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

    /** «Ν.4172/2013», «ΑΡ64Ν4172/13» — κάθε είδος φόρου κλείνει με νόμο. */
    private val LAW_REFERENCE = Regex("""Ν\.?\s?\d{4}\s*/\s*\d{2,4}""")
    private val PERIOD_SLASH = Regex("""(\d{1,2})\s*/\s*(\d{4})""")

    /**
     * «01/06/2026-30/06/2026» — αναγνωρίσιμο και χωρίς την ετικέτα του.
     * Η κλάση παύλας πιάνει και τα τυπογραφικά που βάζει το OCR (‐ έως ―).
     */
    private val DATE_RANGE = Regex(
        """(\d{1,2})/(\d{1,2})/(\d{4})\s*[-‐-―]\s*\d{1,2}/\d{1,2}/\d{4}""",
    )

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
        val clean = normalize(text)
        val rows = branch(clean, text, fileName, driveFileId)
        DebugLog.log(TAG) {
            if (rows.isEmpty()) {
                "«$fileName»: κανένα κλαδί δεν ταίριαξε · " + fingerprints(clean)
            } else {
                "«$fileName»: ${rows.size} γραμμές — " +
                    rows.joinToString { "${it.kind} ${it.amount} ${it.periodMonth}/${it.periodYear}" }
            }
        }
        return rows
    }

    /**
     * Ποια λέξη-κλειδί βρέθηκε και ποια όχι.
     *
     * Μπαίνει στο αρχείο καταγραφής μόνο όταν αποτύχει η αναγνώριση, και είναι
     * το πρώτο πράγμα που δείχνει αν φταίει το OCR ή τα μοτίβα.
     */
    private fun fingerprints(clean: String): String = listOf(
        "Ταυτότητα Οφειλής", "Σημείωμα για Πληρωμή", "Μισθοδοτική Κατάσταση",
        "ΑΠΔ", "Διαφήμισης", "Ποσό δόσης", "Ημερολογιακή Περίοδος",
    ).joinToString(" ") { word ->
        if (looksLike(clean, word)) "✓$word" else "✗$word"
    }

    private fun branch(
        clean: String,
        text: String,
        fileName: String,
        driveFileId: String,
    ): List<DebtEntity> {
        return when {
            looksLike(clean, "Μισθοδοτική Κατάσταση") ->
                payroll(text, clean, fileName, driveFileId)

            looksLike(clean, "Ταυτότητα Οφειλής", "Σημείωμα για Πληρωμή") ->
                aade(clean, fileName, driveFileId)

            looksLike(clean, "Διαφήμισης", "ΔΗΜΟΣΙΟΓΡΑΦΙΚΟΣ") ->
                advertising(clean, fileName, driveFileId)

            looksLike(clean, "ΑΠΔ", "ΑΠΟΔΕΙΚΤΙΚΟΥ ΥΠΟΒΟΛΗΣ") ->
                apd(clean, fileName, driveFileId)

            // Τελευταία γραμμή άμυνας. Το σημείωμα της ΑΑΔΕ είναι το μόνο
            // έντυπο χωρίς επίπεδο κειμένου, άρα φτάνει πάντα μέσω OCR — και
            // αν χαθεί ακόμη και ο τίτλος του, η ταυτότητα οφειλής μένει:
            // τίποτε άλλο στο έντυπο δεν έχει τόσα συνεχόμενα ψηφία.
            debtIdentity(clean).isNotBlank() -> aade(clean, fileName, driveFileId)

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
        val teka = text.contains("ΤΕΚΑ") || normalize(fileName).contains("ΤΕΚΑ")
        val kind = if (teka) DebtKind.TEKA else DebtKind.IKA

        val period = periodOf(
            Regex(
                anchor("Περίοδος") + """\s*(?:ΑΠΟ|ΕΩΣ)?\s*:?\s*(\d{1,2})\s*/\s*(\d{4})""",
            ).find(text),
        ) ?: fromFileName(fileName)

        val amount = amountAfter(text, anchor("Σύνολο Εισφ") + """\S*""")
            ?: amountAfter(text, anchor("Καταβλητέες Εισφορές"))
            ?: return emptyList()

        val days = Regex(
            anchor("Σύνολο Ημερών Ασφάλισης") + """\s*:?\s*(\d{1,4})""",
        ).find(text)?.groupValues?.get(1)
        val wages = amountAfter(text, anchor("Σύνολο Αποδοχών"))

        return listOf(
            debt(
                kind = kind,
                period = period,
                amount = amount,
                reference = rfCode(text),
                // Το έντυπο λέει τι πληρώνεται και για πόση ασφάλιση. Και τα
                // δύο χωράνε, και μαζί εξηγούν το ποσό — σκέτο «ΑΠΔ ΙΚΑ» δεν
                // ξεχώριζε καν τον έναν μήνα από τον άλλο.
                description = buildString {
                    append(if (teka) "Εισφορές ΤΕΚΑ" else "Εισφορές ΙΚΑ")
                    if (days != null) append(" · $days ημέρες ασφάλισης")
                    if (wages != null) append(" · αποδοχές ${wages.asMoney()}")
                    submissionNumber(text)?.let { append(" · υποβολή $it") }
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
        val amount = aadeAmount(text)
        if (amount == null) {
            // Το κλαδί ταίριαξε αλλά το ποσό δεν βρέθηκε: το πιο πιθανό είναι
            // ότι το OCR έσπασε τα δεκαδικά ή έχασε το κόμμα
            DebugLog.log(TAG) {
                "ΑΑΔΕ: δεν βρέθηκε ποσό. Ποσά στο κείμενο: " +
                    ANY_AMOUNT.findAll(text).map { it.value }.take(12).joinToString()
            }
            return emptyList()
        }

        // Η προθεσμία γράφεται δύο φορές: στην ετικέτα της δόσης και στο κείμενο
        val due = Regex(
            anchor("Ποσό δόσης δήλωσης της") + """\s*(\d{1,2}/\d{1,2}/\d{4})""",
        ).find(text)?.groupValues?.get(1)
            ?: Regex(
                anchor("μέχρι τις") + """\s*(\d{1,2}/\d{1,2}/\d{4})""",
            ).find(text)?.groupValues?.get(1)

        return listOf(
            debt(
                kind = DebtKind.AADE,
                period = aadePeriod(text, fileName, due),
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
     * Το ποσό που πρέπει να πληρωθεί.
     *
     * Πρώτα δίπλα στην ετικέτα του, που είναι το ακριβές. Όταν όμως η μηχανή
     * βγάλει το έντυπο **σε στήλες** —πρώτα όλες οι ετικέτες, μετά όλες οι
     * τιμές— η τιμή απέχει εκατοντάδες χαρακτήρες από την ετικέτα της, και
     * καμία απόσταση δεν φτάνει χωρίς να αρχίσει να πιάνει λάθος νούμερα.
     *
     * Τότε μετράει η σειρά: το έντυπο τυπώνει πρώτα το συνολικό ποσό και μετά
     * τη δόση, οπότε και στη στήλη των τιμών η δόση είναι η τελευταία. Και η
     * δόση είναι αυτό που πληρώνεται τώρα.
     */
    private fun aadeAmount(text: String): Double? {
        val beside = amountAfter(text, anchor("Ποσό δόσης"))
            ?: amountAfter(text, anchor("Συνολικό ποσό οφειλής"))
        if (beside != null) return beside

        return ANY_AMOUNT.findAll(text)
            .mapNotNull { money(it.value) }
            .filter { it > 0.0 }
            .lastOrNull()
    }

    /**
     * Ο μήνας αναφοράς της ΑΑΔΕ, με σειρά αξιοπιστίας.
     *
     * Η ετικέτα «Ημερολογιακή Περίοδος» είναι η ακριβέστερη αλλά και η πιο
     * μακριά: δώδεκα γράμματα, δώδεκα ευκαιρίες να τη χαλάσει το OCR. Το εύρος
     * ημερομηνιών από κάτω **δεν χρειάζεται καθόλου ετικέτα** — κανένα άλλο
     * σημείο του εντύπου δεν έχει δύο ημερομηνίες με παύλα ανάμεσα.
     *
     * Τελευταία λύση, το **έτος** της προθεσμίας. Ο μήνας μένει άγνωστος αντί
     * να μαντευτεί, αλλά η οφειλή πέφτει στη σωστή χρονιά· χωρίς έτος έμενε
     * εκτός από κάθε φίλτρο της λίστας, αποθηκευμένη αλλά αόρατη.
     */
    private fun aadePeriod(text: String, fileName: String, due: String?): Period {
        Regex(
            anchor("Ημερολογιακή Περίοδος") + """\s*:?\s*(\d{1,2})/(\d{1,2})/(\d{4})""",
        ).find(text)?.let {
            return Period(it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }

        DATE_RANGE.find(text)?.let {
            return Period(it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }

        fromFileName(fileName).takeIf { it.year > 0 }?.let { return it }

        return due?.substringAfterLast('/')?.toIntOrNull()
            ?.let { Period(0, it) }
            ?: Period.NONE
    }

    /**
     * Η «Ταυτότητα Οφειλής», ως μία συνεχόμενη σειρά ψηφίων.
     *
     * Τυπώνεται σε τριάδες με κενά και το OCR μπορεί να βάλει τελείες ή παύλες
     * ανάμεσα. Αν η ετικέτα δεν βρεθεί καθόλου, ψάχνεται σκέτη μια αρκετά
     * μεγάλη ομάδα ψηφίων: τίποτε άλλο στο έντυπο δεν έχει τόσα.
     */
    internal fun debtIdentity(text: String): String {
        Regex(anchor("Ταυτότητα Οφειλής") + """\s*:?\s*([0-9][0-9 	.\-]{18,50})""").find(text)
            ?.groupValues?.get(1)
            ?.filter { it.isDigit() }
            ?.takeIf { it.length >= 15 }
            ?.let { return it }

        return Regex("""(?<![0-9])([0-9][0-9 	.\-]{22,45}[0-9])(?![0-9])""").findAll(text)
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
    internal fun taxKind(text: String): String? = labelledTaxKind(text) ?: taxKindAboveRange(text)

    /**
     * Το είδος φόρου όταν χάθηκε η ετικέτα του.
     *
     * Δύο σταθερά του εντύπου το πιάνουν χωρίς αυτήν: κάθεται **ακριβώς πάνω**
     * από την ημερολογιακή περίοδο, και τελειώνει σε παραπομπή νόμου
     * («Ν.4172/2013», «ΑΡ64Ν4172/13»). Ισχύουν και στις δύο διατάξεις που
     * βγάζει το OCR — και με την τιμή δίπλα στην ετικέτα, και σε στήλες.
     *
     * Όταν η γραμμή του νόμου έχει λιγότερες από τρεις λέξεις είναι συνέχεια
     * της από πάνω, οπότε παίρνονται και οι δύο· αλλιώς στέκει μόνη της. Έτσι
     * δεν κολλάει από πάνω το όνομα της ΔΟΥ.
     */
    private fun taxKindAboveRange(text: String): String? {
        val lines = text.lines()
        val range = lines.indexOfFirst { DATE_RANGE.containsMatchIn(it) }
        if (range <= 0) return null

        var at = range - 1
        while (at >= 0 && lines[at].isBlank()) at--
        if (at < 0) return null

        val lawLine = lines[at].trim()
        if (!LAW_REFERENCE.containsMatchIn(lawLine)) return null

        val parts = mutableListOf(lawLine)
        if (lawLine.split(Regex("""\s+""")).size < 3) {
            var above = at - 1
            while (above >= 0 && lines[above].isBlank()) above--
            val previous = lines.getOrNull(above)?.trim().orEmpty()
            val usable = previous.isNotBlank() &&
                !ANY_AMOUNT.containsMatchIn(previous) &&
                !DATE.containsMatchIn(previous) &&
                previous.any { it.isLetter() }
            if (usable) parts.add(0, previous)
        }
        return parts.joinToString(" ").replace(Regex("""\s+"""), " ").take(90).ifBlank { null }
    }

    private fun labelledTaxKind(text: String): String? {
        val next = listOf(
            "Ημερολογιακή", "Συνολικό", "Ποσό", "Ταυτότητα", "Ημ/νία",
            "Προσοχή", "ΔΟΥ", "Τύπος",
        ).joinToString("|") { anchor(it) }
        val match = Regex(
            anchor("Είδος Φόρου") + """\s*:?\s*([\s\S]{1,140}?)\s*(?=$next|$)""",
        ).find(text) ?: return null
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
        val amount = amountAfter(text, anchor("Εισφορές") + """\s*€""")
            ?: amountAfter(text, anchor("Εισφορές"))
            ?: return emptyList()

        val period = periodOf(
            Regex(anchor("Περίοδος") + """\s*:?\s*(\d{1,2})\s*/\s*(\d{4})""").find(text),
        ) ?: fromFileName(fileName)

        val cost = amountAfter(text, anchor("Κόστος Διαφήμισης") + """\s*€?""")
        val rate = amountAfter(text, anchor("Ποσοστό Εισφορών"))

        return listOf(
            debt(
                kind = DebtKind.ADVERTISING,
                period = period,
                amount = amount,
                reference = rfCode(text),
                description = buildString {
                    append("Εισφορές διαφήμισης ΕΔΟΕΑΠ")
                    if (rate != null) append(" ${trimZeros(rate)}%")
                    if (cost != null) append(" · κόστος διαφήμισης ${cost.asMoney()}")
                    submissionNumber(text)?.let { append(" · υποβολή $it") }
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
    private fun payroll(
        raw: String,
        text: String,
        fileName: String,
        driveFileId: String,
    ): List<DebtEntity> {
        val period = periodOf(
            Regex(
                anchor("Μισθοδοτική Κατάσταση") + """\s*(\d{1,2})\s*/\s*(\d{4})""",
            ).find(text),
        ) ?: fromFileName(fileName)

        val people = readPeople(raw, text)
        val rows = people.flatMap { rowsFor(it, period, fileName, driveFileId) }
        return merge(rows)
    }

    /**
     * Οι εργαζόμενοι, με τα ονόματα **όπως ακριβώς τυπώνονται**.
     *
     * Το ταίριασμα γίνεται στο κανονικοποιημένο κείμενο, γιατί εκεί δουλεύουν
     * τα μοτίβα· το όνομα όμως κόβεται από το αυθεντικό, στην ίδια θέση. Στη
     * μισθοδοσία τα ονόματα είναι συχνά λατινικά, και η κανονικοποίηση —που
     * γυρίζει τα λατινικά δίδυμα σε ελληνικά— θα έκανε το «BUTT HURARA»
     * «ΒUΤΤ ΗURΑRΑ». Η [normalize] δεν αλλάζει μήκος, οπότε οι θέσεις
     * συμπίπτουν γράμμα προς γράμμα.
     */
    private fun readPeople(raw: String, text: String): List<Person> {
        val header = Regex("""^\s*(\d{1,3})\s+([Α-Ω0-9]{2,6})\s+(\S.*)$""")
        val detail = Regex("""^\s*([Α-Ω]{2})\s+\S""")
        val people = mutableListOf<Person>()
        val printed = raw.lines()

        text.lines().forEachIndexed { index, line ->
            val head = header.find(line)
            if (head != null && head.groupValues[3].firstOrNull()?.isLetter() == true) {
                val printedName = printed.getOrNull(index)
                    ?.takeIf { it.length == line.length }
                    ?.slice(head.groups[3]!!.range)
                    ?.takeIf { it.isNotBlank() }
                people += Person(head.groupValues[2], nameOf(printedName ?: head.groupValues[3]))
                return@forEachIndexed
            }

            val person = people.lastOrNull() ?: return@forEachIndexed

            detail.find(line)?.let { match ->
                val gross = ANY_AMOUNT.find(line)?.value?.let(::money)
                if (gross != null && gross > 0.0) {
                    person.details += Detail(match.groupValues[1], gross)
                }
                return@forEachIndexed
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

    /** «Αριθμ. Υποβολής» και «Αριθμός Υποβολής» — το ίδιο πράγμα. */
    private fun submissionNumber(text: String): String? = Regex(
        anchor("Αριθμ") + """[Α-Ω]*\.?\s*""" + anchor("Υποβολής") + """\s*:?\s*(\d+)""",
    ).find(text)?.groupValues?.get(1)

    /** «2,00» → «2». Το ποσοστό διαβάζεται καλύτερα χωρίς μηδενικά. */
    private fun trimZeros(value: Double): String =
        if (value == Math.floor(value)) value.toInt().toString() else value.asMoney().removeSuffix(" €")

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
        val upper = normalize(fileName)
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
        val match = Regex("""RF\d{2}[0-9A-ZΑ-Ω ]{10,40}""").find(text) ?: return ""
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
