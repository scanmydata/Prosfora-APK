package gr.prosfora.app.debt

import java.text.Normalizer

/**
 * Φέρνει το κείμενο ενός παραστατικού σε μορφή που αντέχει το OCR.
 *
 * Τα σαρωμένα έντυπα δεν γυρίζουν ποτέ γράμμα προς γράμμα σωστά στα ελληνικά.
 * Τρία πράγματα χαλάνε σχεδόν πάντα:
 *
 *  * **οι τόνοι** — «Ποσό δόσης» βγαίνει «Ποσο δοσης»,
 *  * **το τελικό σίγμα** — «Οφειλής» βγαίνει «Οφειλησ»,
 *  * **τα λατινικά δίδυμα** — το Ο, το Α και το Τ έχουν ίδιο σχήμα με τα
 *    λατινικά O, A, T, και η μηχανή διαλέγει χωρίς κανόνα.
 *
 * Ένα μοτίβο γραμμένο με τόνους δεν ταιριάζει σε τίποτε από αυτά. Γι' αυτό
 * **και το κείμενο και οι ετικέτες** περνούν από την [normalize] πριν
 * συγκριθούν: όλα γίνονται κεφαλαία, άτονα, ελληνικά.
 *
 * Η [normalize] **δεν αλλάζει μήκος**. Αυτό δεν είναι λεπτομέρεια: επιτρέπει
 * να βρίσκουμε κάτι στο κανονικοποιημένο κείμενο και να το κόβουμε από το
 * αυθεντικό στην ίδια θέση. Έτσι ένα «BUTT HURARA» στη μισθοδοσία μένει
 * λατινικό στην οθόνη, αντί να γίνει «ΒUΤΤ ΗURΑRΑ».
 */
internal object DebtText {

    /**
     * Λατινικά κεφαλαία που μοιράζονται γλυφο με ελληνικά — όλα γυρίζουν στο
     * ελληνικό, ώστε οι δύο εκδοχές να πέφτουν στο ίδιο σημείο.
     */
    private val LOOKALIKE = mapOf(
        'A' to 'Α', 'B' to 'Β', 'E' to 'Ε', 'Z' to 'Ζ', 'H' to 'Η', 'I' to 'Ι',
        'K' to 'Κ', 'M' to 'Μ', 'N' to 'Ν', 'O' to 'Ο', 'P' to 'Ρ', 'T' to 'Τ',
        'Y' to 'Υ', 'X' to 'Χ',
    )

    /** Ψηφία που το OCR βάζει μέσα σε λέξεις, στη θέση του γράμματος. */
    private val DIGIT_TWINS = mapOf('Ο' to "Ο0", 'Ι' to "Ι1")

    private val MARKS = Regex("""\p{Mn}""")

    /** Κεφαλαία, άτονα, χωρίς λατινικά δίδυμα — με το μήκος ανέπαφο. */
    fun normalize(text: String): String = buildString(text.length) {
        text.forEach { append(fold(it)) }
    }

    private fun fold(ch: Char): Char {
        if (ch == ' ') return ' '
        val bare = if (ch.code < 0x80) {
            ch
        } else {
            val stripped = MARKS.replace(
                Normalizer.normalize(ch.toString(), Normalizer.Form.NFD),
                "",
            )
            // Ό,τι δεν λύνεται σε έναν χαρακτήρα μένει ως έχει: η αντιστοιχία
            // θέσεων με το αυθεντικό κείμενο είναι πιο πολύτιμη
            if (stripped.length == 1) stripped[0] else return ch
        }
        val upper = bare.uppercaseChar()
        return LOOKALIKE[upper] ?: upper
    }

    /** Από πόσους χαρακτήρες και πάνω συγχωρείται ένα λάθος γράμμα. */
    private const val SLACK_FROM = 7

    /** Ανάμεσα σε κάθε δύο γράμματα χωράει όσο κενό θέλει η μηχανή. */
    private const val GLUE = """\s*"""

    /**
     * Μια σταθερή ετικέτα ως μοτίβο ανεκτικό στο OCR.
     *
     * Δύο ελευθερίες, και οι δύο από παρατηρημένες αστοχίες:
     *
     *  * **κενά όπου να 'ναι** — η μηχανή σπάει λέξεις («ΗΜΕΡ ΟΛΟΓΙΑΚΗ») και
     *    αλλάζει γραμμή μέσα σε ετικέτα, οπότε τα γράμματα κολλάνε με `\s*`,
     *  * **ένα λάθος γράμμα** σε ετικέτες από [SLACK_FROM] χαρακτήρες και πάνω.
     *    Μια ετικέτα δώδεκα γραμμάτων έχει δώδεκα ευκαιρίες να χαλάσει, και μία
     *    αρκούσε για να χαθεί ολόκληρη η περίοδος της οφειλής. Με έντεκα σωστά
     *    γράμματα η ταύτιση παραμένει βέβαιη.
     */
    fun anchor(label: String): String {
        val parts = normalize(label).filterNot { it == ' ' }.map(::charPattern)
        if (parts.size < SLACK_FROM) return parts.joinToString(GLUE)
        // Κάθε εναλλακτική αφήνει μία θέση ελεύθερη· η θέση που «ταιριάζει με
        // τα πάντα» δέχεται και το σωστό γράμμα, οπότε το ακέραιο λεκτικό
        // καλύπτεται κι αυτό
        return parts.indices.joinToString(separator = "|", prefix = "(?:", postfix = ")") { free ->
            parts.mapIndexed { at, part -> if (at == free) "." else part }.joinToString(GLUE)
        }
    }

    private fun charPattern(ch: Char): String = when {
        DIGIT_TWINS.containsKey(ch) -> "[" + DIGIT_TWINS[ch] + "]"
        ch.isLetterOrDigit() -> ch.toString()
        else -> Regex.escape(ch.toString())
    }

    /** Υπάρχει κάποια από τις [words] στο ήδη κανονικοποιημένο [text]; */
    fun looksLike(text: String, vararg words: String): Boolean =
        words.any { Regex(anchor(it)).containsMatchIn(text) }
}
