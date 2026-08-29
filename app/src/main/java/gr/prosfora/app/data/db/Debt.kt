package gr.prosfora.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.LocalDate
import java.time.YearMonth


enum class DebtAgency(val label: String, val folder: String) {
    EFKA("ΙΚΑ & ΤΕΚΑ", "ΙΚΑ-ΤΕΚΑ"),
    AADE("ΑΑΔΕ", "ΑΑΔΕ"),
    ADVERTISING("Διαφημιστικά τέλη", "Διαφημιστικά τέλη"),
    PAYROLL("Μισθοδοσία", "Μισθοδοσία"),
}

enum class DebtKind(val label: String, val agency: DebtAgency) {
    IKA("ΙΚΑ — κύρια ασφάλιση", DebtAgency.EFKA),
    TEKA("ΤΕΚΑ — επικουρική", DebtAgency.EFKA),
    AADE("Βεβαιωμένη οφειλή", DebtAgency.AADE),
    ADVERTISING("Εισφορές διαφήμισης", DebtAgency.ADVERTISING),
    PAYROLL("Μισθοδοσία", DebtAgency.PAYROLL),
    PAYROLL_BONUS("Δώρο", DebtAgency.PAYROLL),
    ;

    val perPerson: Boolean get() = agency == DebtAgency.PAYROLL
}

@Entity(
    tableName = "debts",
    indices = [Index("periodYear", "periodMonth"), Index("kind"), Index("amIka")],
)
data class DebtEntity(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val kind: DebtKind = DebtKind.AADE,
    val periodMonth: Int = 0,
    val periodYear: Int = 0,
    val dueDay: Long? = null,
    val amount: Double = 0.0,
    val reference: String = "",
    val description: String = "",
    val personName: String = "",
    val personCode: String = "",
    /** ΑΜ Ι.Κ.Α. της μισθοδοσίας — canonical αναγνωριστικό εργαζομένου. */
    val amIka: String = "",
    val paid: Boolean = false,
    val paidAt: Long? = null,
    val paidDay: Long? = null,
    val source: String = "",
    val createdBy: String = "",
    val driveFileId: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
) {
    val agency: DebtAgency get() = kind.agency
    val imported: Boolean get() = source.isNotBlank()
    val periodLabel: String get() = if (periodMonth in 1..12 && periodYear > 0) "$periodMonth/$periodYear" else "—"
    val periodKey: Int get() = periodYear * 100 + periodMonth
    val title: String get() = when {
        kind.perPerson && personName.isNotBlank() -> personName
        description.isNotBlank() -> description
        else -> kind.label
    }
    fun overdue(today: LocalDate = LocalDate.now()): Boolean = !paid && dueDay != null && dueDay < today.toEpochDay()
    fun daysLeft(today: LocalDate = LocalDate.now()): Long? = dueDay?.let { it - today.toEpochDay() }

    companion object {
        fun idFor(kind: DebtKind, year: Int, month: Int, reference: String, person: String): String {
            val seed = listOf(kind.name, year.toString(), month.toString(), reference, person).joinToString("|")
            val digest = java.security.MessageDigest.getInstance("SHA-1").digest(seed.toByteArray(Charsets.UTF_8))
            return "debt-" + digest.joinToString("") { "%02x".format(it) }.take(16)
        }
        fun defaultDue(kind: DebtKind, year: Int, month: Int): Long? {
            if (month !in 1..12 || year <= 0) return null
            val period = YearMonth.of(year, month)
            val target = if (kind.agency == DebtAgency.PAYROLL) period else period.plusMonths(1)
            return target.atEndOfMonth().toEpochDay()
        }
    }
}

/**
 * Ο εργαζόμενος ταυτοποιείται αποκλειστικά από το ΑΜ ΙΚΑ. Δεν χρησιμοποιείται
 * μοναδικό database index στο πεδίο επειδή παλιές εγκαταστάσεις έχουν legacy
 * rows με κενό ΑΜ ΙΚΑ· το canonical upsert γίνεται από το repository.
 */
@Entity(tableName = "employees", indices = [Index(value = ["amIka"])])
data class EmployeeEntity(
    @PrimaryKey val id: String,
    val amIka: String,
    val name: String,
    val alias: String = "",
    val code: String = "",
    val leftDay: Long? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
) {
    val display: String get() = alias.ifBlank { name }
    fun gone(today: LocalDate = LocalDate.now()): Boolean = leftDay != null && leftDay <= today.toEpochDay()

    companion object {
        fun normalizeIka(raw: String): String = raw.filter(Char::isDigit)
        fun idForAmIka(amIka: String): String = "employee-ika-${normalizeIka(amIka)}"
        /** Compatibility helper for legacy UI call-sites; canonical identity remains AM IKA. */
        @Deprecated("Use idForAmIka(amIka) for employee identity")
        fun idFor(name: String): String = legacyIdFor(name)
        fun legacyIdFor(name: String): String = name.trim().uppercase().replace(Regex("""\s+"""), " ")
    }
}
