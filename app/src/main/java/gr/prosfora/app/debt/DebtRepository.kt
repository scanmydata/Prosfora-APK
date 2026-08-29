package gr.prosfora.app.debt

import android.content.Context
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.data.db.ProsforaDatabase
import gr.prosfora.app.debug.DebugLog
import gr.prosfora.app.google.GoogleSettings
import kotlinx.coroutines.flow.Flow

class DebtRepository(context: Context) {

    private val settings = GoogleSettings(context)
    private val database = ProsforaDatabase.get(context)
    private val debts = database.debtDao()
    private val employees = database.employeeDao()

    fun observeAll(): Flow<List<DebtEntity>> = debts.observeAll()

    fun observeEmployees(): Flow<List<EmployeeEntity>> = employees.observeAll()

    suspend fun saveEmployee(employee: EmployeeEntity) =
        employees.upsert(employee.copy(updatedAt = System.currentTimeMillis()))

    /**
     * Σβήνει έναν εργαζόμενο από το ευρετήριο.
     * Οι οφειλές του μένουν ώστε να διατηρείται το ιστορικό μισθοδοσίας.
     */
    suspend fun deleteEmployee(id: String) =
        employees.softDelete(id, System.currentTimeMillis())

    suspend fun save(debt: DebtEntity) = debts.upsert(
        debt.copy(
            updatedAt = System.currentTimeMillis(),
            createdBy = debt.createdBy.ifBlank { settings.ownerEmail },
        ),
    ).also {
        // Μια χειροκίνητη επεξεργασία οφειλής μισθοδοσίας μπορεί να είναι η
        // μοναδική διαθέσιμη γραμμή για τον εργαζόμενο. Κρατάμε το ευρετήριο
        // συγχρονισμένο και σε αυτή τη διαδρομή.
        if (debt.kind.perPerson && debt.personName.isNotBlank()) {
            rememberPeople(listOf(debt))
        }
    }

    suspend fun saveAll(items: List<DebtEntity>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val merged = items.map { incoming ->
            val existing = debts.getById(incoming.id)
            if (existing == null) {
                incoming.copy(updatedAt = now, createdBy = settings.ownerEmail)
            } else {
                incoming.copy(
                    paid = existing.paid,
                    paidAt = existing.paidAt,
                    paidDay = existing.paidDay,
                    createdBy = existing.createdBy.ifBlank { settings.ownerEmail },
                    createdAt = existing.createdAt,
                    updatedAt = now,
                    deleted = false,
                )
            }
        }
        debts.upsertAll(merged)

        // Το index δεν πρέπει να εξαρτάται από το αν ο χρήστης έχει ήδη ανοίξει
        // την οθόνη «Εργαζόμενοι». Ανανεώνεται αμέσως μετά από κάθε εισαγωγή.
        rememberPeople(merged)

        // Rebuild από όλο το τοπικό ιστορικό ώστε παλαιότερες μισθοδοσίες που
        // είχαν εισαχθεί πριν δημιουργηθεί το ευρετήριο να εμφανίζονται επίσης.
        rememberPeople(debts.allForSync())
    }

    /**
     * Συμπληρώνει το ευρετήριο από τις ήδη αποθηκευμένες μισθοδοτικές οφειλές.
     * Χρειάζεται για συσκευές που είχαν εισάγει μισθοδοσία πριν δημιουργηθεί
     * το ευρετήριο εργαζομένων.
     */
    suspend fun ensureEmployeesFromExistingDebts() {
        rememberPeople(debts.allForSync())
    }

    /**
     * Ενημερώνει/αναβιώνει το ευρετήριο χωρίς να σβήνει ψευδώνυμα ή άλλα
     * στοιχεία που έχει ήδη διορθώσει ο χρήστης.
     *
     * Το παλιό insertMissing(... IGNORE) άφηνε έναν soft-deleted εργαζόμενο
     * μόνιμα κρυφό από την οθόνη, ακόμη κι όταν εμφανιζόταν ξανά σε νέα
     * μισθοδοσία. Τώρα γίνεται πραγματικό upsert με deleted=false.
     */
    private suspend fun rememberPeople(items: List<DebtEntity>) {
        val existing = employees.allForSync().associateBy { it.id }
        val people = items
            .filter { it.kind.perPerson && it.personName.isNotBlank() }
            .map { debt ->
                val id = EmployeeEntity.idFor(debt.personName)
                val old = existing[id]
                EmployeeEntity(
                    id = id,
                    name = debt.personName.trim(),
                    alias = old?.alias ?: "",
                    code = debt.personCode.ifBlank { old?.code ?: "" },
                    leftDay = old?.leftDay,
                    updatedAt = System.currentTimeMillis(),
                    deleted = false,
                )
            }
            .distinctBy { it.id }

        people.forEach { employee -> employees.upsert(employee) }
        if (people.isNotEmpty()) {
            DebugLog.log("employees") {
                "ευρετήριο εργαζομένων ενημερώθηκε · payrollRows=${items.count { it.kind.perPerson && it.personName.isNotBlank() }} · employeesUpserted=${people.size}"
            }
        }
    }

    suspend fun setPaid(id: String, paid: Boolean, day: Long? = null) {
        val now = System.currentTimeMillis()
        val existing = debts.getById(id) ?: return
        debts.upsert(
            existing.copy(
                paid = paid,
                paidAt = if (paid) existing.paidAt ?: now else null,
                paidDay = if (paid) day else null,
                updatedAt = now,
            ),
        )
    }

    suspend fun delete(id: String) = debts.softDelete(id, System.currentTimeMillis())

    suspend fun delete(ids: Collection<String>) {
        val now = System.currentTimeMillis()
        ids.forEach { debts.softDelete(it, now) }
    }

    suspend fun deleteFromFile(fileName: String, driveFileId: String): Int {
        val now = System.currentTimeMillis()
        val victims = debts.allForSync().filter {
            !it.deleted && (
                (driveFileId.isNotBlank() && it.driveFileId == driveFileId) ||
                    (driveFileId.isBlank() && fileName.isNotBlank() && it.source == fileName)
                )
        }
        victims.forEach { debts.softDelete(it.id, now) }
        // Διατήρηση ευρετηρίου από το υπόλοιπο ιστορικό μετά από διαγραφή αρχείου.
        rememberPeople(debts.allForSync())
        return victims.size
    }

    suspend fun importedFileIds(): Set<String> = debts.importedFileIds().toSet()
}
