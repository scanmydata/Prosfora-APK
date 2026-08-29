package gr.prosfora.app.debt

import android.content.Context
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.data.db.ProsforaDatabase
import gr.prosfora.app.google.GoogleSettings
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

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

    suspend fun save(debt: DebtEntity) {
        val normalized = normalizeByDueDate(debt)
        debts.upsert(
            normalized.copy(
                updatedAt = System.currentTimeMillis(),
                createdBy = normalized.createdBy.ifBlank { settings.ownerEmail },
            ),
        )
    }

    suspend fun saveAll(items: List<DebtEntity>) {
        val now = System.currentTimeMillis()
        val merged = items.map { rawIncoming ->
            // Ο μήνας που εμφανίζεται στην εφαρμογή είναι πάντα ο μήνας της
            // λήξης πληρωμής όταν υπάρχει λήξη. Π.χ. περίοδος 7/2026 με λήξη
            // 31/08/2026 ανήκει στις οφειλές Αυγούστου 2026.
            var incoming = normalizeByDueDate(rawIncoming)
            var existing = debts.getById(incoming.id)

            // Αν το parser είχε δημιουργήσει το id με τον παλιό μήνα αναφοράς
            // και υπάρχει ήδη εγγραφή με αυτό το id από διαφορετικό αρχείο,
            // μην κληρονομήσουμε κατά λάθος την κατάστασή της. Δημιούργησε
            // canonical id με βάση τον μήνα λήξης.
            if (
                existing != null &&
                incoming.source.isNotBlank() &&
                existing.driveFileId.isNotBlank() &&
                existing.driveFileId != incoming.driveFileId
            ) {
                incoming = incoming.copy(
                    id = DebtEntity.idFor(
                        incoming.kind,
                        incoming.periodYear,
                        incoming.periodMonth,
                        incoming.reference,
                        incoming.personName,
                    ),
                )
                existing = debts.getById(incoming.id)
            }

            if (existing == null) {
                // Νέα εισαγωγή ξεκινά ΠΑΝΤΑ ως απλήρωτη. Η κατάσταση πληρωμής
                // δεν πρέπει να έρχεται από OCR/parser ή από άσχετη εγγραφή.
                incoming.copy(
                    paid = false,
                    paidAt = null,
                    paidDay = null,
                    updatedAt = now,
                    createdBy = settings.ownerEmail,
                )
            } else {
                // Κρατάμε την πραγματική κατάσταση πληρωμής μόνο όταν πρόκειται
                // για την ίδια καταχώρηση/ίδιο αρχείο. Έτσι ένα νέο PDF που
                // συμπίπτει σε reference δεν εμφανίζεται κατά λάθος εξοφλημένο.
                val sameImportedFile =
                    incoming.driveFileId.isNotBlank() &&
                        existing.driveFileId.isNotBlank() &&
                        incoming.driveFileId == existing.driveFileId
                val sameManualRecord =
                    incoming.driveFileId.isBlank() && existing.driveFileId.isBlank()

                incoming.copy(
                    paid = if (sameImportedFile || sameManualRecord) existing.paid else false,
                    paidAt = if (sameImportedFile || sameManualRecord) existing.paidAt else null,
                    paidDay = if (sameImportedFile || sameManualRecord) existing.paidDay else null,
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

    /**
     * Ομαδοποίηση οφειλών με βάση τη λήξη πληρωμής. Η περίοδος που τυπώνει το
     * έγγραφο είναι πληροφορία αναφοράς, όχι ο μήνας στον οποίο εμφανίζεται η
     * οφειλή στην εφαρμογή.
     */
    private fun normalizeByDueDate(debt: DebtEntity): DebtEntity {
        val due = debt.dueDay?.let(LocalDate::ofEpochDay) ?: return debt
        return debt.copy(
            periodMonth = due.monthValue,
            periodYear = due.year,
        )
    }
}
