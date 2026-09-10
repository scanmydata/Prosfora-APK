package gr.prosfora.app.data.db

/**
 * Πότε μια αλλαγή σε εργαζόμενο που ήρθε από το κοινόχρηστο φύλλο νικάει την
 * τοπική καρτέλα.
 *
 * Τα πεδία του χρήστη —ψευδώνυμο, αποχώρηση, περίοδοι— συγκρίνονται με το δικό
 * τους ρολόι, το [EmployeeEntity.editedAt]. Παλιά συγκρίνονταν με το κοινό
 * [EmployeeEntity.updatedAt], που το χτυπούσαν και οι αυτόματες ενημερώσεις από
 * τις μισθοδοσίες. Έτσι γινόταν το «αλλάζω ψευδώνυμο και μου το ξαναφέρνει»:
 * ο δείκτης εργαζομένων κατέβαζε το ρολόι της καρτέλας στην ώρα της τελευταίας
 * μισθοδοσίας, η παλιά γραμμή του φύλλου φαινόταν νεότερη και κέρδιζε.
 */
object EmployeeEdits {

    /** Η γραμμή του φύλλου κουβαλάει νεότερη αλλαγή χρήστη από την τοπική. */
    fun remoteWins(local: EmployeeEntity, remote: EmployeeEntity): Boolean =
        remote.editedAt > local.editedAt

    /**
     * Πριν από αυτή την έκδοση καμία καρτέλα δεν είχε [EmployeeEntity.editedAt],
     * οπότε οι δύο πλευρές ισοβαθμούν στο μηδέν. Τότε δεν κερδίζει καμία —
     * γεμίζουν μόνο τα κενά της τοπικής από το φύλλο, ώστε ένα ψευδώνυμο που
     * είχε βάλει ο άλλος χρήστης να μη χαθεί. Η πρώτη νέα αλλαγή αποφασίζει.
     */
    fun fillBlanks(local: EmployeeEntity, remote: EmployeeEntity): EmployeeEntity? {
        if (local.editedAt != 0L || remote.editedAt != 0L) return null
        val filled = local.copy(
            alias = local.alias.ifBlank { remote.alias },
            leftDay = local.leftDay ?: remote.leftDay,
            periods = local.periods.ifBlank { remote.periods },
        )
        return filled.takeIf { it != local }
    }

    /**
     * Η αποχώρηση που πρέπει να ανακοινωθεί σε αυτή τη συσκευή, αν υπάρχει.
     *
     * Μόνο όταν η αλλαγή ήρθε από το φύλλο, είναι νεότερη, **ορίζει** ημερομηνία
     * που δεν υπήρχε, και την έκανε άλλος. Ο συντάκτης δεν ειδοποιείται — ούτε
     * σε δεύτερη συσκευή του, γιατί το [EmployeeEntity.editedBy] είναι το email
     * του, όχι η συσκευή.
     */
    fun departureToAnnounce(local: EmployeeEntity, remote: EmployeeEntity, me: String): Long? {
        if (!remoteWins(local, remote)) return null
        val left = remote.leftDay ?: return null
        if (left == local.leftDay) return null
        val author = remote.editedBy.trim()
        if (author.isNotEmpty() && author.equals(me.trim(), ignoreCase = true)) return null
        return left
    }
}
