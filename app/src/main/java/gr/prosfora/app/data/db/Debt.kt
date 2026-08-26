package gr.prosfora.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.YearMonth
import java.util.UUID

/**
 * Ο φορέας προς τον οποίο οφείλεται το ποσό.
 *
 * Το ΙΚΑ και το ΤΕΚΑ κάθονται στην ίδια ομάδα επειδή προκύπτουν από την ίδια
 * ΑΠΔ και πληρώνονται μαζί — αλλά μένουν χωριστά είδη, γιατί είναι δύο
 * ξεχωριστά ταυτόχρονα αποδεικτικά με δικό του RF το καθένα.
 */
enum class DebtAgency(val label: String, val folder: String) {
    EFKA("ΙΚΑ & ΤΕΚΑ", "ΙΚΑ-ΤΕΚΑ"),
    AADE("ΑΑΔΕ", "ΑΑΔΕ"),
    ADVERTISING("Διαφημιστικά τέλη", "Διαφημιστικά τέλη"),
    PAYROLL("Μισθοδοσία", "Μισθοδοσία"),
}

/** Το συγκεκριμένο είδος οφειλής μέσα στον φορέα. */
enum class DebtKind(val label: String, val agency: DebtAgency) {
    IKA("ΙΚΑ — κύρια ασφάλιση", DebtAgency.EFKA),
    TEKA("ΤΕΚΑ — επικουρική", DebtAgency.EFKA),
    AADE("Βεβαιωμένη οφειλή", DebtAgency.AADE),
    ADVERTISING("Εισφορές διαφήμισης", DebtAgency.ADVERTISING),
    PAYROLL("Μισθοδοσία", DebtAgency.PAYROLL),
    PAYROLL_BONUS("Δώρο", DebtAgency.PAYROLL),
    ;

    /** Στη μισθοδοσία ο τίτλος της γραμμής είναι ο εργαζόμενος, όχι το είδος. */
    val perPerson: Boolean get() = agency == DebtAgency.PAYROLL
}

/**
 * Μία οφειλή: ένα ποσό, ένας μήνας αναφοράς, μία ημερομηνία λήξης.
 *
 * Η μισθοδοσία σπάει σε μία εγγραφή ανά άτομο — αυτό είναι το ζητούμενο, να
 * φαίνεται το πληρωτέο του καθενός και όχι μόνο το σύνολο της κατάστασης.
 */
@Entity(
    tableName = "debts",
    indices = [Index("periodYear", "periodMonth"), Index("kind")],
)
data class DebtEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val kind: DebtKind = DebtKind.AADE,
    /** Μήνας αναφοράς 1–12 και έτος· 0 όταν η οφειλή δεν αφορά περίοδο. */
    val periodMonth: Int = 0,
    val periodYear: Int = 0,
    /** Λήξη πληρωμής ως epoch day. */
    val dueDay: Long? = null,
    val amount: Double = 0.0,
    /** Ταυτότητα οφειλής ή RF — πάντα ως ενιαία σειρά χαρακτήρων, χωρίς κενά. */
    val reference: String = "",
    /** Είδος φόρου, αριθμός υποβολής, ό,τι περιγράφει την οφειλή. */
    val description: String = "",
    /** Μισθοδοσία: ο εργαζόμενος και ο κωδικός του στην κατάσταση. */
    val personName: String = "",
    val personCode: String = "",
    val paid: Boolean = false,
    val paidAt: Long? = null,
    /**
     * Πότε πληρώθηκε στην πραγματικότητα, ως epoch day.
     *
     * Χωριστό από το [paidAt], που είναι απλώς η στιγμή που τσεκαρίστηκε το
     * κουτάκι: μια οφειλή σημειώνεται συχνά μέρες μετά την πληρωμή της.
     */
    val paidDay: Long? = null,
    /** Το όνομα του αρχείου από το οποίο διαβάστηκε· κενό αν γράφτηκε με το χέρι. */
    val source: String = "",
    /** Το αντίγραφο του παραστατικού στο Drive, για να ανοίγει με ένα άγγιγμα. */
    val driveFileId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
) {

    val agency: DebtAgency get() = kind.agency

    /** Ήρθε από ανάγνωση παραστατικού και όχι από τα χέρια του χρήστη. */
    val imported: Boolean get() = source.isNotBlank()

    /** «7/2026» — ο μήνας αναφοράς όπως γράφεται στα ίδια τα παραστατικά. */
    val periodLabel: String
        get() = if (periodMonth in 1..12 && periodYear > 0) "$periodMonth/$periodYear" else "—"

    /** Κλειδί ταξινόμησης: νεότερος μήνας πρώτος. */
    val periodKey: Int get() = periodYear * 100 + periodMonth

    val title: String
        get() = when {
            kind.perPerson && personName.isNotBlank() -> personName
            description.isNotBlank() -> description
            else -> kind.label
        }

    fun overdue(today: LocalDate = LocalDate.now()): Boolean =
        !paid && dueDay != null && dueDay < today.toEpochDay()

    /** Πόσες μέρες μένουν ως τη λήξη· αρνητικό σημαίνει πέρασε. */
    fun daysLeft(today: LocalDate = LocalDate.now()): Long? =
        dueDay?.let { it - today.toEpochDay() }

    companion object {
        /**
         * Σταθερό αναγνωριστικό από το περιεχόμενο, ώστε το ίδιο παραστατικό να
         * μη μπει δεύτερη φορά αν ξανασαρωθεί ο φάκελος. Η ταυτότητα οφειλής
         * είναι μοναδική· εκεί που λείπει (μισθοδοσία) μετράει ο εργαζόμενος.
         */
        fun idFor(kind: DebtKind, year: Int, month: Int, reference: String, person: String): String {
            val seed = listOf(kind.name, year.toString(), month.toString(), reference, person)
                .joinToString("|")
            val digest = java.security.MessageDigest.getInstance("SHA-1")
                .digest(seed.toByteArray(Charsets.UTF_8))
            return "debt-" + digest.joinToString("") { "%02x".format(it) }.take(16)
        }

        /**
         * Η προθεσμία που ισχύει όταν το παραστατικό δεν τη γράφει: εισφορές και
         * τέλη πληρώνονται ως το τέλος του επόμενου μήνα από την περίοδο, ενώ η
         * μισθοδοσία μέσα στον ίδιο τον μήνα. Είναι εκτίμηση και αλλάζει.
         */
        fun defaultDue(kind: DebtKind, year: Int, month: Int): Long? {
            if (month !in 1..12 || year <= 0) return null
            val period = YearMonth.of(year, month)
            // Η μισθοδοσία —και τα δώρα— πληρώνονται μέσα στον ίδιο μήνα
            val target =
                if (kind.agency == DebtAgency.PAYROLL) period else period.plusMonths(1)
            return target.atEndOfMonth().toEpochDay()
        }
    }
}

/**
 * Ένας εργαζόμενος, όπως εμφανίζεται στις μισθοδοτικές καταστάσεις.
 *
 * Υπάρχει για δύο λόγους: για το ευρετήριο, ώστε να φαίνονται όλοι μαζί με τα
 * ποσά τους, και για τα **ψευδώνυμα** — στις καταστάσεις τα ονόματα γράφονται
 * με λατινικά κεφαλαία και συχνά ανάποδα, οπότε ο χρήστης θέλει να τα βλέπει
 * όπως τους ξέρει ο ίδιος.
 */
@Entity(tableName = "employees")
data class EmployeeEntity(
    /** Το όνομα κανονικοποιημένο — δεν αλλάζει ποτέ, είναι ο σύνδεσμος. */
    @PrimaryKey val id: String,
    /** Όπως τυπώνεται στην κατάσταση. */
    val name: String,
    /** Πώς θέλει να το βλέπει ο χρήστης· κενό σημαίνει «όπως τυπώνεται». */
    val alias: String = "",
    val code: String = "",
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
) {
    val display: String get() = alias.ifBlank { name }

    companion object {
        fun idFor(name: String): String =
            name.trim().uppercase().replace(Regex("""\s+"""), " ")
    }
}
