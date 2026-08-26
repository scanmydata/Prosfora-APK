package gr.prosfora.app.debt

import android.content.Context
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.data.db.ProsforaDatabase
import kotlinx.coroutines.flow.Flow

class DebtRepository(context: Context) {

    private val database = ProsforaDatabase.get(context)
    private val debts = database.debtDao()
    private val employees = database.employeeDao()

    fun observeAll(): Flow<List<DebtEntity>> = debts.observeAll()

    fun observeEmployees(): Flow<List<EmployeeEntity>> = employees.observeAll()

    suspend fun saveEmployee(employee: EmployeeEntity) =
        employees.upsert(employee.copy(updatedAt = System.currentTimeMillis()))

    suspend fun save(debt: DebtEntity) =
        debts.upsert(debt.copy(updatedAt = System.currentTimeMillis()))

    /**
     * Αποθηκεύει ό,τι διάβασε η σάρωση. Ό,τι υπάρχει ήδη κρατάει την κατάσταση
     * πληρωμής του: ένα παραστατικό που ξαναδιαβάστηκε δεν σημαίνει ότι η
     * οφειλή ξαναγεννήθηκε απλήρωτη.
     */
    suspend fun saveAll(items: List<DebtEntity>) {
        val now = System.currentTimeMillis()
        val merged = items.map { incoming ->
            val existing = debts.getById(incoming.id)
            if (existing == null) {
                incoming.copy(updatedAt = now)
            } else {
                incoming.copy(
                    paid = existing.paid,
                    paidAt = existing.paidAt,
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
     * Καταχωρεί στο ευρετήριο όποιον εργαζόμενο πρωτοεμφανίζεται.
     *
     * Μόνο όσους λείπουν: αν ο χρήστης έχει βάλει ψευδώνυμο, μια νέα μισθοδοσία
     * δεν πρέπει να το σβήσει.
     */
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

    /**
     * Σβήνει ό,τι ήρθε από ένα συγκεκριμένο αρχείο.
     *
     * Ο δρόμος επιστροφής όταν εισαχθεί λάθος παραστατικό: μια μισθοδοσία
     * φτιάχνει δέκα γραμμές, και το να σβήνονται μία-μία είναι τιμωρία.
     */
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

    /** Ποια αρχεία του Drive έχουν ήδη διαβαστεί — η σάρωση τα προσπερνάει. */
    suspend fun importedFileIds(): Set<String> = debts.importedFileIds().toSet()
}
