package gr.prosfora.app.debug

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Τοπικό αρχείο καταγραφής, για όταν κάτι αποτυγχάνει μόνο πάνω στη συσκευή.
 *
 * Υπάρχει επειδή η ανάγνωση παραστατικών δεν αναπαράγεται από τον υπολογιστή:
 * το κείμενο έρχεται από OCR της Google, φτιάχνεται τη στιγμή που ζητιέται,
 * και το μόνο που έφτανε πίσω ήταν ένα «καμία οφειλή δεν αναγνωρίστηκε». Χωρίς
 * το ίδιο το κείμενο, κάθε διόρθωση είναι εικασία.
 *
 * Γράφει σε αρχείο και όχι στο logcat: ο χρήστης δεν έχει καλώδιο και εργαλεία,
 * έχει όμως το κινητό του και ένα κουμπί αντιγραφής.
 *
 * **Σβηστό από προεπιλογή.** Το κείμενο των παραστατικών περιέχει ΑΦΜ, ονόματα
 * και ποσά — δεν καταγράφεται χωρίς να το ζητήσει ο χρήστης, και ένα κουμπί το
 * σβήνει ολόκληρο.
 */
object DebugLog {

    /** Πάνω από αυτό, το αρχείο κόβεται στη μέση και κρατάει το πρόσφατο. */
    private const val MAX_BYTES = 512 * 1024

    /** Πόσο κείμενο κρατιέται από ένα μεγάλο απόσπασμα. */
    private const val DUMP_LIMIT = 8000

    private val lock = Any()

    /**
     * Το `SimpleDateFormat` δεν αντέχει ταυτόχρονη χρήση, και εδώ γράφουν
     * νήματα IO και οθόνης μαζί — γι' αυτό ζει πίσω από το ίδιο κλείδωμα με το
     * αρχείο.
     */
    private val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale("el"))

    private fun now(): String = synchronized(lock) { stamp.format(Date()) }

    @Volatile
    private var target: File? = null

    @Volatile
    var enabled: Boolean = false
        private set

    /** Καλείται μία φορά με το ξεκίνημα και ξανά όποτε αλλάξει ο διακόπτης. */
    fun configure(context: Context, on: Boolean) {
        synchronized(lock) {
            enabled = on
            if (target == null) {
                val folder = File(context.applicationContext.filesDir, "diagnostics")
                runCatching { folder.mkdirs() }
                target = File(folder, FILE_NAME)
            }
        }
        if (on) line("— καταγραφή ενεργή —")
    }

    fun file(context: Context): File =
        target ?: File(File(context.applicationContext.filesDir, "diagnostics"), FILE_NAME)

    /** Μια γραμμή με χρονοσήμανση. Το [tag] δείχνει ποιος μιλάει. */
    fun log(tag: String, message: String) {
        if (!enabled) return
        line("${now()}  [$tag] $message")
    }

    /**
     * Το ίδιο, όταν το μήνυμα κοστίζει να φτιαχτεί.
     *
     * Με σβηστή καταγραφή το [message] δεν εκτελείται καθόλου. Χρειάζεται όπου
     * το μήνυμα ξανακάνει δουλειά — π.χ. ξεψαχνίζει το PDF ή τρέχει μοτίβα —
     * γιατί αλλιώς το κόστος της διάγνωσης το πληρώνουν και όσοι δεν τη θέλουν.
     */
    inline fun log(tag: String, message: () -> String) {
        if (enabled) log(tag, message())
    }

    /**
     * Ένα ολόκληρο απόσπασμα κειμένου — τυπικά η έξοδος του OCR.
     *
     * Μπαίνει σε πλαίσιο ώστε να ξεχωρίζει από τις γραμμές γύρω του, και
     * κόβεται: ένα PDF πολλών σελίδων θα γέμιζε το αρχείο και δεν θα διαβαζόταν
     * από κανέναν.
     */
    fun dump(tag: String, label: String, text: String) {
        if (!enabled) return
        val body = if (text.length <= DUMP_LIMIT) {
            text
        } else {
            text.take(DUMP_LIMIT) + "\n…(κόπηκε στους $DUMP_LIMIT από ${text.length})"
        }
        line(
            buildString {
                append(now()).append("  [").append(tag).append("] ")
                append(label).append(" — ").append(text.length).append(" χαρακτήρες\n")
                append("┌──────\n")
                body.lineSequence().forEach { append("│ ").append(it).append('\n') }
                append("└──────")
            },
        )
    }

    fun read(context: Context): String {
        val log = file(context)
        return runCatching { if (log.exists()) log.readText() else "" }.getOrDefault("")
    }

    fun clear(context: Context) {
        synchronized(lock) { runCatching { file(context).delete() } }
    }

    private fun line(text: String) {
        val log = target ?: return
        synchronized(lock) {
            runCatching {
                // Κόβεται στη μέση αντί να σβήνεται: μια αποτυχία συνήθως έχει
                // την αιτία της λίγο πριν, και δεν πρέπει να χαθεί
                if (log.length() > MAX_BYTES) {
                    val kept = log.readText().takeLast(MAX_BYTES / 2)
                    log.writeText("…(παλαιότερα κόπηκαν)\n$kept")
                }
                log.appendText(text + "\n")
            }
        }
    }

    const val FILE_NAME = "prosfora.log"
}
