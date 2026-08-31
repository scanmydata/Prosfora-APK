package gr.prosfora.app.notify

import android.content.Context
import gr.prosfora.app.data.db.DebtEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Πόσες **οφειλές** μπήκαν και δεν τις έχει δει ακόμη ο χρήστης.
 *
 * Το σηματάκι του μενού μετρούσε ως τώρα *αρχεία* του Drive που άλλαξαν. Ένα
 * αρχείο μισθοδοσίας όμως φέρνει δέκα οφειλές, και ένα «+1» δεν έλεγε τίποτα
 * γι' αυτό που πραγματικά προστέθηκε.
 *
 * Κρατιούνται αναγνωριστικά και όχι απλός μετρητής: ο ίδιος συγχρονισμός
 * μπορεί να τρέξει δύο φορές —στο άνοιγμα και από το alarm— και ένας μετρητής
 * θα διπλασίαζε τα ίδια νούμερα.
 */
object NewDebtsBadge {

    private const val KEY = "unseen_debt_ids"

    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("prosfora_badges", Context.MODE_PRIVATE)

    /** Διαβάζει ό,τι είχε μείνει από προηγούμενη εκτέλεση. */
    fun restore(context: Context) {
        _count.value = stored(context).size
    }

    fun record(context: Context, debts: List<DebtEntity>) {
        if (debts.isEmpty()) return
        val ids = stored(context) + debts.map { it.id }
        prefs(context).edit().putStringSet(KEY, ids).apply()
        _count.value = ids.size
    }

    /** Ο χρήστης άνοιξε τις Οφειλές: τα είδε. */
    fun clear(context: Context) {
        if (_count.value == 0 && stored(context).isEmpty()) return
        prefs(context).edit().remove(KEY).apply()
        _count.value = 0
    }

    private fun stored(context: Context): Set<String> =
        prefs(context).getStringSet(KEY, emptySet()).orEmpty()
}
