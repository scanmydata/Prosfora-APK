package gr.prosfora.app.ui.debts

import androidx.compose.ui.graphics.Color
import gr.prosfora.app.data.db.DebtKind

/**
 * Χρώμα ανά είδος οφειλής.
 *
 * Το ΙΚΑ και το ΤΕΚΑ κάθονται στην ίδια ομάδα, οπότε παίρνουν δύο αποχρώσεις
 * του ίδιου μπλε: φαίνεται με τη μία ότι είναι συγγενικά και ταυτόχρονα ότι
 * είναι δύο χωριστές πληρωμές.
 */
val IkaBlue = Color(0xFF1565C0)
val TekaBlue = Color(0xFF64B5F6)
val AadeAmber = Color(0xFFEF6C00)
val AdvertisingTeal = Color(0xFF00897B)
val PayrollGreen = Color(0xFF2E7D32)
val BonusLime = Color(0xFF9CCC65)

val DebtKind.color: Color
    get() = when (this) {
        DebtKind.IKA -> IkaBlue
        DebtKind.TEKA -> TekaBlue
        DebtKind.AADE -> AadeAmber
        DebtKind.ADVERTISING -> AdvertisingTeal
        DebtKind.PAYROLL -> PayrollGreen
        // Το δώρο είναι μισθοδοσία, αλλά πληρώνεται χωριστά: ίδια οικογένεια,
        // πιο ανοιχτός τόνος
        DebtKind.PAYROLL_BONUS -> BonusLime
    }

/** Τα ονόματα των μηνών, για τις επικεφαλίδες των ομάδων. */
val MONTH_NAMES = listOf(
    "Ιανουάριος", "Φεβρουάριος", "Μάρτιος", "Απρίλιος", "Μάιος", "Ιούνιος",
    "Ιούλιος", "Αύγουστος", "Σεπτέμβριος", "Οκτώβριος", "Νοέμβριος", "Δεκέμβριος",
)

fun monthLabel(month: Int, year: Int): String = when {
    month in 1..12 && year > 0 -> "${MONTH_NAMES[month - 1]} $year"
    year > 0 -> year.toString()
    else -> "Χωρίς περίοδο"
}
