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

    suspend fun importedFileIds(): Set<String> = debts.importedFileIds().toSet()

    suspend fun legacyPayrollFileIdsMissingIka(): List<String> = debts.legacyPayrollFileIdsMissingIka()

    suspend fun deleteLegacyPayrollRows(fileIds: Collection<String>) {
        if (fileIds.isEmpty()) return
        debts.deleteLegacyPayrollRows(fileIds.toList())
    }

    suspend fun saveEmployee(employee: EmployeeEntity) {
        val ika = EmployeeEntity.normalizeIka(employee.amIka)
        employees.upsert(
            employee.copy(
                id = if (ika.isNotBlank()) ika else employee.id,
                amIka = ika,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun deleteEmployee(id: String) =
        employees.softDelete(id, System.currentTimeMillis())

    suspend fun save(debt: DebtEntity) {
        val normalized = normalizeByDueDate(debt.copy(amIka = EmployeeEntity.normalizeIka(debt.amIka)))
        debts.upsert(
            normalized.copy(
                updatedAt = System.currentTimeMillis(),
                createdBy = normalized.createdBy.ifBlank { settings.ownerEmail },
            ),
        )
        rebuildEmployeeIndex()
    }

    suspend fun saveAll(items: List<DebtEntity>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val merged = items.map { rawIncoming ->
            var incoming = normalizeByDueDate(rawIncoming.copy(amIka = EmployeeEntity.normalizeIka(rawIncoming.amIka)))
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
                    deleted = false,
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
        rebuildEmployeeIndex()
    }

    suspend fun ensureEmployeesFromExistingDebts() = rebuildEmployeeIndex()

    /** Compatibility entry point used by the sync layer. */
    suspend fun repairEmployeeIndex() = rebuildEmployeeIndex()

    private suspend fun rebuildEmployeeIndex() {
        val stored = employees.allForSync()
        val storedByIka = stored
            .mapNotNull { employee ->
                val ika = EmployeeEntity.normalizeIka(employee.amIka)
                if (ika.isBlank()) null else ika to employee
            }
            .groupBy({ it.first }, { it.second })

        val payroll = debts.allForSync()
            .asSequence()
            .filter { !it.deleted && it.kind.perPerson }
            .mapNotNull { debt ->
                val ika = EmployeeEntity.normalizeIka(debt.amIka)
                if (ika.isBlank()) null else ika to debt
            }
            .toList()

        val grouped = payroll.groupBy({ it.first }, { it.second })
        val activeIka = grouped.keys
        var deleted = 0

        stored.forEach { employee ->
            val ika = EmployeeEntity.normalizeIka(employee.amIka)
            if (ika.isBlank() || ika !in activeIka || employee.id != ika) {
                employees.hardDelete(employee.id)
                deleted++
            }
        }

        val canonical = grouped.map { (ika, rows) ->
            val existing = storedByIka[ika]
                ?.firstOrNull { it.id == ika }
                ?: storedByIka[ika]?.maxByOrNull { it.updatedAt }

            EmployeeEntity(
                id = ika,
                amIka = ika,
                name = rows.firstOrNull { it.personName.isNotBlank() }?.personName?.trim()
                    ?: existing?.name.orEmpty(),
                alias = existing?.alias.orEmpty(),
                code = rows.firstOrNull { it.personCode.isNotBlank() }?.personCode?.trim()
                    ?: existing?.code.orEmpty(),
                leftDay = existing?.leftDay,
                updatedAt = rows.maxOfOrNull { it.updatedAt } ?: existing?.updatedAt ?: System.currentTimeMillis(),
                deleted = false,
            )
        }.sortedBy { it.name.uppercase() }

        if (canonical.isNotEmpty()) employees.upsertAll(canonical)

        DebugLog.log(
            "employees",
            "employee DB rebuild complete · unique AM IKA=${canonical.size} · hard-deleted stale/duplicates=$deleted",
        )
    }

    suspend fun setPaid(id: String, paid: Boolean, day: Long? = null) {
        debts.markPaid(id, paid, if (paid) (day ?: System.currentTimeMillis()) else null, System.currentTimeMillis())
    }

    suspend fun delete(id: String) = delete(listOf(id))

    suspend fun delete(ids: Collection<String>) {
        val now = System.currentTimeMillis()
        ids.forEach { debts.softDelete(it, now) }
        rebuildEmployeeIndex()
    }

    suspend fun deleteFromFile(source: String, driveFileId: String) {
        if (driveFileId.isBlank()) return
        val ids = debts.allForSync()
            .filter { it.driveFileId == driveFileId && (source.isBlank() || it.source == source) }
            .map { it.id }
        delete(ids)
    }

    private fun normalizeByDueDate(debt: DebtEntity): DebtEntity {
        if (debt.dueDay != null) return debt
        return debt.copy(dueDay = DebtEntity.defaultDue(debt.kind, debt.periodYear, debt.periodMonth))
    }
}
