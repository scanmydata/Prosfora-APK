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

    suspend fun legacyPayrollFileIdsMissingIka(): List<String> =
        debts.legacyPayrollFileIdsMissingIka()

    suspend fun deleteLegacyPayrollRows(fileIds: Collection<String>) {
        if (fileIds.isEmpty()) return
        debts.deleteLegacyPayrollRows(fileIds.toList())
    }

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
        repairEmployeeIndex()
        rememberPeople(listOf(normalized), reviveDeleted = true)
        repairEmployeeIndex()
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

        repairEmployeeIndex()
        rememberPeople(merged, reviveDeleted = true)
        rememberPeople(debts.allForSync(), reviveDeleted = true)
        repairEmployeeIndex()
    }

    suspend fun ensureEmployeesFromExistingDebts() {
        rememberPeople(debts.allForSync(), reviveDeleted = false)
        repairEmployeeIndex()
    }

    suspend fun repairEmployeeIndex() {
        val existing = employees.allForSync()
        if (existing.isEmpty()) return

        val byIka = existing
            .mapNotNull { employee ->
                val ika = EmployeeEntity.normalizeIka(employee.amIka)
                if (ika.isBlank()) null else ika to employee
            }
            .groupBy({ it.first }, { it.second })

        var removed = 0
        byIka.forEach { (ika, rows) ->
            val survivor = rows.firstOrNull { it.id == ika }
                ?: rows.filter { !it.deleted }.maxByOrNull { it.updatedAt }
                ?: rows.maxByOrNull { it.updatedAt }
                ?: return@forEach

            val canonical = survivor.copy(id = ika, amIka = ika)
            employees.upsert(canonical)
            rows.forEach { row ->
                if (row.id != ika) {
                    employees.hardDelete(row.id)
                    removed++
                }
            }
        }

        DebugLog.log(
            "employees",
            "employee DB repair complete · unique AM IKA=${byIka.size} · hard-deleted duplicates=$removed",
        )
    }

    private suspend fun rememberPeople(items: List<DebtEntity>, reviveDeleted: Boolean) {
        val payroll = items.filter {
            it.kind.perPerson && (
                it.personName.isNotBlank() || it.personCode.isNotBlank() || it.amIka.isNotBlank()
            )
        }
        if (payroll.isEmpty()) return

        val inferredIkaByFallback = payroll
            .groupBy { employeeFallbackKey(it.personCode, it.personName) }
            .mapValues { (_, rows) ->
                rows.map { EmployeeEntity.normalizeIka(it.amIka) }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
            }

        payroll
            .groupBy { employeeFallbackKey(it.personCode, it.personName) }
            .values
            .forEach { rows ->
                val representative = rows.maxByOrNull { it.updatedAt } ?: rows.first()
                val ika = EmployeeEntity.normalizeIka(representative.amIka)
                    .ifBlank { inferredIkaByFallback[employeeFallbackKey(representative.personCode, representative.personName)].orEmpty() }

                if (ika.isBlank()) return@forEach

                val existing = employees.findByAmIka(ika)
                val merged = EmployeeEntity(
                    id = ika,
                    amIka = ika,
                    name = rows.firstOrNull { it.personName.isNotBlank() }?.personName?.trim()
                        ?: existing?.name.orEmpty(),
                    alias = existing?.alias.orEmpty(),
                    code = rows.firstOrNull { it.personCode.isNotBlank() }?.personCode?.trim()
                        ?: existing?.code.orEmpty(),
                    leftDay = existing?.leftDay,
                    updatedAt = representative.updatedAt,
                    deleted = if (reviveDeleted) false else (existing?.deleted ?: false),
                )
                employees.upsert(merged)
            }
    }

    private fun employeeFallbackKey(code: String, name: String): String =
        listOf(code.trim(), name.trim().uppercase().replace(Regex("\\s+"), " "))
            .joinToString("|")

    private fun normalizeByDueDate(debt: DebtEntity): DebtEntity {
        if (debt.dueDay != null) return debt
        return debt.copy(dueDay = DebtEntity.defaultDue(debt.kind, debt.periodYear, debt.periodMonth))
    }

    private suspend fun importedFileIds(): List<String> = debts.importedFileIds()
}
