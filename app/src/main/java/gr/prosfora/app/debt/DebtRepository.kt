package gr.prosfora.app.debt

import android.content.Context
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.data.db.ProsforaDatabase
import gr.prosfora.app.debug.DebugLog
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
        rememberPeople(listOf(normalized))
    }

    suspend fun saveAll(items: List<DebtEntity>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val merged = items.map { rawIncoming ->
            var incoming = normalizeByDueDate(rawIncoming)
            var existing = debts.getById(incoming.id)

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
                incoming.copy(
                    paid = false,
                    paidAt = null,
                    paidDay = null,
                    updatedAt = now,
                    createdBy = settings.ownerEmail,
                )
            } else {
                val sameImportedFile = incoming.driveFileId.isNotBlank() &&
                    existing.driveFileId.isNotBlank() &&
                    incoming.driveFileId == existing.driveFileId
                val sameManualRecord = incoming.driveFileId.isBlank() && existing.driveFileId.isBlank()

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

        // Η δημιουργία του index γίνεται αμέσως μετά την εισαγωγή και δεν
        // περιμένει να ανοίξει ο χρήστης το menu «Εργαζόμενοι».
        rememberPeople(merged)
        rememberPeople(debts.allForSync())
    }

    suspend fun ensureEmployeesFromExistingDebts() {
        rememberPeople(debts.allForSync())
    }

    /**
     * Ο εργαζόμενος είναι μοναδικός αποκλειστικά με βάση το ΑΜ Ι.Κ.Α.
     * Όλες οι γραμμές μισθοδοσίας με το ίδιο ΑΜ ΙΚΑ μπαίνουν στην ίδια καρτέλα.
     */
    private suspend fun rememberPeople(items: List<DebtEntity>) {
        val payroll = items.filter { it.kind.perPerson && it.amIka.isNotBlank() }
        if (payroll.isEmpty()) return

        val existing = employees.allForSync()
        val aliasByIka = existing.filter { it.amIka.isNotBlank() && it.alias.isNotBlank() }
            .associateBy { EmployeeEntity.normalizeIka(it.amIka) }
        val aliasByName = existing.filter { it.alias.isNotBlank() }
            .associateBy { it.name.trim().uppercase().replace(Regex("\\s+"), " ") }

        val people = payroll
            .groupBy { EmployeeEntity.normalizeIka(it.amIka) }
            .mapNotNull { (ika, rows) ->
                if (ika.isBlank()) return@mapNotNull null
                val old = aliasByIka[ika]
                val firstName = rows.firstOrNull { it.personName.isNotBlank() }?.personName.orEmpty()
                val oldByName = aliasByName[firstName.trim().uppercase().replace(Regex("\\s+"), " ")]
                EmployeeEntity(
                    id = EmployeeEntity.idForAmIka(ika),
                    amIka = ika,
                    name = firstName,
                    alias = old?.alias ?: oldByName?.alias.orEmpty(),
                    code = rows.firstOrNull { it.personCode.isNotBlank() }?.personCode.orEmpty(),
                    leftDay = old?.leftDay ?: oldByName?.leftDay,
                    updatedAt = System.currentTimeMillis(),
                    deleted = false,
                )
            }

        if (people.isEmpty()) return
        employees.softDeleteAll(System.currentTimeMillis())
        employees.upsertAll(people)
        DebugLog.log("employees", "canonical index updated · unique AM IKA=${people.size}")
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
        rememberPeople(debts.allForSync())
        return victims.size
    }

    suspend fun importedFileIds(): Set<String> = debts.importedFileIds().toSet()

    private fun normalizeByDueDate(debt: DebtEntity): DebtEntity {
        val due = debt.dueDay?.let(LocalDate::ofEpochDay) ?: return debt
        return debt.copy(periodMonth = due.monthValue, periodYear = due.year)
    }
}
