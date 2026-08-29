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

        // First remove stale employee duplicates already present in the local DB.
        repairEmployeeIndex()

        // Then add/update employees from the newly imported payroll rows.
        rememberPeople(merged, reviveDeleted = true)

        // Finally rebuild the canonical index from the complete debt history and
        // physically remove duplicate employee rows from the database.
        rememberPeople(debts.allForSync(), reviveDeleted = true)
        repairEmployeeIndex()
    }

    suspend fun ensureEmployeesFromExistingDebts() {
        rememberPeople(debts.allForSync(), reviveDeleted = false)
        repairEmployeeIndex()
    }

    /**
     * HARD reconciliation of the employee table.
     *
     * The canonical identity is exactly the normalized AM IKA. Any previous
     * legacy/fallback row that carries the same AM IKA but another id is
     * physically deleted. This is intentional: deleted legacy rows were the
     * source of duplicate employee cards and also confused Sheet sync.
     *
     * A row already using id == AM IKA is preferred. Otherwise, the newest
     * non-deleted row is kept; if none is active, the newest row is kept.
     */
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

            val canonical = survivor.copy(
                id = ika,
                amIka = ika,
            )
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

    /**
     * Creates one employee row per canonical AM IKA. Rows without AM IKA are
     * still allowed as legacy fallback records so an old payroll import is not
     * lost, but once AM IKA is known all related rows converge to the AM IKA id.
     */
    private suspend fun rememberPeople(items: List<DebtEntity>, reviveDeleted: Boolean) {
        val payroll = items.filter {
            it.kind.perPerson && (
                it.personName.isNotBlank() ||
                    it.personCode.isNotBlank() ||
                    it.amIka.isNotBlank()
                )
        }
        if (payroll.isEmpty()) return

        // Infer AM IKA for rows from the same payroll batch by code+name. This
        // prevents a row with a temporarily missing AM IKA from becoming a
        // second fallback employee when another row already identifies the same
        // person with a valid AM IKA.
        val inferredIkaByFallback = payroll
            .groupBy { employeeFallbackKey(it.personCode, it.personName) }
            .mapValues { (_, rows) ->
                rows.map { EmployeeEntity.normalizeIka(it.amIka) }
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
            }

        val existing = employees.allForSync()
        val existingByIka = existing
            .filter { EmployeeEntity.normalizeIka(it.amIka).isNotBlank() }
            .groupBy { EmployeeEntity.normalizeIka(it.amIka) }
        val existingByFallback = existing
            .filter { it.amIka.isBlank() }
            .associateBy { employeeFallbackKey(it.code, it.name) }
        val existingByName = existing
            .filter { it.amIka.isBlank() && it.name.isNotBlank() }
            .groupBy { canonicalPersonName(it.name) }

        val people = payroll
            .groupBy { row ->
                val rawIka = EmployeeEntity.normalizeIka(row.amIka)
                val inferredIka = inferredIkaByFallback[employeeFallbackKey(row.personCode, row.personName)].orEmpty()
                employeeKey(rawIka.ifBlank { inferredIka }, row.personCode, row.personName)
            }
            .mapNotNull { (_, rows) ->
                val first = rows.firstOrNull() ?: return@mapNotNull null
                val ika = rows
                    .map { EmployeeEntity.normalizeIka(it.amIka) }
                    .firstOrNull { it.isNotBlank() }
                    ?: inferredIkaByFallback[employeeFallbackKey(first.personCode, first.personName)].orEmpty()
                val name = rows.firstOrNull { it.personName.isNotBlank() }
                    ?.personName
                    ?.trim()
                    .orEmpty()
                val code = rows.firstOrNull { it.personCode.isNotBlank() }
                    ?.personCode
                    ?.trim()
                    .orEmpty()

                if (ika.isBlank() && name.isBlank() && code.isBlank()) return@mapNotNull null

                val old = if (ika.isNotBlank()) {
                    existingByIka[ika]
                        ?.firstOrNull { !it.deleted }
                        ?: existingByIka[ika]?.firstOrNull()
                } else {
                    existingByFallback[employeeFallbackKey(code, name)]
                        ?: existingByName[canonicalPersonName(name)]?.firstOrNull()
                }

                // An intentional manual deletion is respected during passive
                // rebuilds. Fresh payroll imports may revive it.
                if (!reviveDeleted && old?.deleted == true) return@mapNotNull null

                val canonicalId = if (ika.isNotBlank()) {
                    EmployeeEntity.idForAmIka(ika)
                } else {
                    old?.id ?: fallbackEmployeeId(code, name)
                }

                EmployeeEntity(
                    id = canonicalId,
                    amIka = ika,
                    name = name.ifBlank { old?.name.orEmpty() },
                    alias = old?.alias.orEmpty(),
                    code = code.ifBlank { old?.code.orEmpty() },
                    leftDay = old?.leftDay,
                    updatedAt = System.currentTimeMillis(),
                    deleted = false,
                )
            }

        if (people.isEmpty()) return
        employees.upsertAll(people)
    }

    private fun employeeKey(amIka: String, code: String, name: String): String {
        val ika = EmployeeEntity.normalizeIka(amIka)
        return if (ika.isNotBlank()) "ika:$ika" else employeeFallbackKey(code, name)
    }

    private fun employeeFallbackKey(code: String, name: String): String =
        "fallback:${code.trim()}|${canonicalPersonName(name)}"

    private fun canonicalPersonName(name: String): String =
        name.trim().uppercase().replace(Regex("\\s+"), " ")

    private fun fallbackEmployeeId(code: String, name: String): String =
        "employee-fallback-${java.util.UUID.nameUUIDFromBytes(employeeFallbackKey(code, name).toByteArray(Charsets.UTF_8))}"

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
        rememberPeople(debts.allForSync(), reviveDeleted = false)
        repairEmployeeIndex()
        return victims.size
    }

    suspend fun importedFileIds(): Set<String> = debts.importedFileIds().toSet()

    private fun normalizeByDueDate(debt: DebtEntity): DebtEntity {
        val due = debt.dueDay?.let(LocalDate::ofEpochDay) ?: return debt
        return debt.copy(periodMonth = due.monthValue, periodYear = due.year)
    }
}
