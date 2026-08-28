package gr.prosfora.app.debt

import android.content.Context
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.data.db.ProsforaDatabase
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
    )

    suspend fun saveAll(items: List<DebtEntity>) {
        val now = System.currentTimeMillis()
        val merged = items.map { incoming ->
            val existing = debts.getById(incoming.id)
            if (existing == null) {
                incoming.copy(updatedAt = now, createdBy = settings.ownerEmail)
            } else {
                incoming.copy(
                    paid = existing.paid,
                    paidAt = existing.paidAt,
                    createdBy = existing.createdBy.ifBlank { settings.ownerEmail },
                    createdAt = existing.createdAt,
                    updatedAt = now,
                    deleted = false,
                )
            }
        }
        debts.upsertAll(merged)
        rememberPeople(items)
    }

    /**
     * Συμπληρώνει το ευρετήριο από τις ήδη αποθηκευμένες μισθοδοτικές οφειλές.
     * Χρειάζεται για συσκευές που είχαν εισάγει μισθοδοσία πριν δημιουργηθεί
     * το ευρετήριο εργαζομένων.
     */
    suspend fun ensureEmployeesFromExistingDebts() {
        rememberPeople(debts.allForSync())
    }

    private suspend fun rememberPeople(items: List<DebtEntity>) {
        val people = items
            .filter { it.personName.isNotBlank() }
            .map {
                EmployeeEntity(
                    id = EmployeeEntity.idFor(it.personName),
                    name = it.personName,
                    code = it.personCode,
                )
            }
            .distinctBy { it.id }
        if (people.isNotEmpty()) employees.insertMissing(people)
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
        return victims.size
    }

    suspend fun importedFileIds(): Set<String> = debts.importedFileIds().toSet()
}
