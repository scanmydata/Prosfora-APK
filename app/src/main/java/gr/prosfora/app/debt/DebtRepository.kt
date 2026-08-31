package gr.prosfora.app.debt

import android.content.Context
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeAliasRegistry
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.data.db.ProsforaDatabase
import gr.prosfora.app.debug.DebugLog
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.sync.PayrollImportSession
import gr.prosfora.app.sync.PayrollEmployeeSnapshotStore
import gr.prosfora.app.sync.PayrollInsuranceDaysStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onEach
import org.json.JSONObject

class DebtRepository(context: Context) {
    private val settings = GoogleSettings(context)
    private val database = ProsforaDatabase.get(context)
    private val debts = database.debtDao()
    private val employees = database.employeeDao()
    private val appContext = context.applicationContext

    fun observeAll(): Flow<List<DebtEntity>> = debts.observeAll()
    fun observeEmployees(): Flow<List<EmployeeEntity>> = employees.observeAll().onEach(EmployeeAliasRegistry::refresh)

    suspend fun importedFileIds(): Set<String> = debts.importedFileIds().toSet()

    /** Ό,τι υπάρχει ήδη στη βάση, από οποιονδήποτε χρήστη της. */
    suspend fun knownDebtIds(): Set<String> = debts.knownDebtIds().toSet()
    suspend fun legacyPayrollFileIdsMissingIka(): List<String> = debts.legacyPayrollFileIdsMissingIka()

    /** Only incomplete payroll files are allowed through the already-imported gate. */
    suspend fun payrollFileIdsNeedingSnapshot(): Set<String> {
        val employeeByIka = employees.allForSync().associateBy { EmployeeEntity.normalizeIka(it.amIka) }
        return debts.allForSync()
            .asSequence()
            .filter { !it.deleted && it.kind.perPerson && it.driveFileId.isNotBlank() }
            .filter { debt ->
                val ika = EmployeeEntity.normalizeIka(debt.amIka)
                if (ika.isBlank() || debt.periodYear <= 0 || debt.periodMonth !in 1..12) return@filter true
                val employee = employeeByIka[ika] ?: return@filter true
                val key = "%04d-%02d".format(debt.periodYear, debt.periodMonth)
                val row = runCatching { JSONObject(employee.payrollSummaryJson).optJSONObject(key) }.getOrNull()
                row == null ||
                    !row.has("payable") ||
                    !row.has("insuranceCost") ||
                    !row.has("insuranceDays")
            }
            .map { it.driveFileId }
            .toSet()
    }

    suspend fun deleteLegacyPayrollRows(fileIds: Collection<String>) {
        if (fileIds.isNotEmpty()) debts.deleteLegacyPayrollRows(fileIds.toList(), System.currentTimeMillis())
    }

    suspend fun unpaidDebts(): List<DebtEntity> {
        EmployeeAliasRegistry.refresh(employees.allForSync())
        return debts.unpaid()
    }

    suspend fun saveEmployee(employee: EmployeeEntity) {
        val ika = EmployeeEntity.normalizeIka(employee.amIka)
        val saved = employee.copy(
            id = if (ika.isNotBlank()) ika else employee.id,
            amIka = ika,
            updatedAt = System.currentTimeMillis(),
            deleted = false,
        )
        employees.upsert(saved)
        if (saved.id.isNotBlank()) settings.forgetDeletedEmployee(saved.id)
        EmployeeAliasRegistry.refresh(employees.allForSync())
    }

    suspend fun deleteEmployee(id: String) = employees.softDelete(id, System.currentTimeMillis())

    /** Permanently remove the employee and create a sync tombstone. */
    suspend fun deleteEmployeeFromDatabase(id: String) {
        val employee = employees.allForSync().firstOrNull { it.id == id }
        val ika = EmployeeEntity.normalizeIka(employee?.amIka ?: id)
        settings.rememberDeletedEmployee(if (ika.isNotBlank()) ika else id)
        // Ο εργαζόμενος φεύγει, οι μισθοδοσίες του μένουν σημειωμένες ως
        // διαγραμμένες: είναι πληρωμές που έγιναν, και το φύλλο του Drive
        // πρέπει να τις δείχνει σβησμένες, όχι να μην τις έχει ποτέ δει
        if (ika.isNotBlank()) debts.retirePayrollForEmployee(ika, System.currentTimeMillis())
        employees.hardDelete(id)
        EmployeeAliasRegistry.refresh(employees.allForSync())
    }

    suspend fun save(debt: DebtEntity) {
        val normalized = normalizeByDueDate(debt.copy(amIka = EmployeeEntity.normalizeIka(debt.amIka)))
        debts.upsert(normalized.copy(updatedAt = System.currentTimeMillis(), createdBy = normalized.createdBy.ifBlank { settings.ownerEmail }))
        rebuildEmployeeIndex()
    }

    suspend fun saveAll(items: List<DebtEntity>) {
        if (items.isEmpty()) return
        val now = System.currentTimeMillis()
        val merged = items.map { rawIncoming ->
            var incoming = normalizeByDueDate(rawIncoming.copy(amIka = EmployeeEntity.normalizeIka(rawIncoming.amIka)))
            var existing = debts.getById(incoming.id)

            if (existing != null && incoming.source.isNotBlank() && existing.driveFileId.isNotBlank() && existing.driveFileId != incoming.driveFileId) {
                incoming = incoming.copy(id = DebtEntity.idFor(incoming.kind, incoming.periodYear, incoming.periodMonth, incoming.reference, incoming.personName))
                existing = debts.getById(incoming.id)
            }

            if (existing == null) {
                incoming.copy(paid = false, paidAt = null, paidDay = null, updatedAt = now, createdBy = settings.ownerEmail, deleted = false)
            } else {
                val sameImportedFile = incoming.driveFileId.isNotBlank() && existing.driveFileId.isNotBlank() && incoming.driveFileId == existing.driveFileId
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

        PayrollImportSession.consume()?.let { (ocrText, stagedDebts) ->
            val effective = merged.filter { m -> stagedDebts.any { s -> s.id == m.id } }.ifEmpty { merged }
            if (effective.any { it.kind.perPerson }) {
                PayrollInsuranceDaysStore.record(appContext, ocrText, effective)
                PayrollEmployeeSnapshotStore.record(appContext, ocrText, effective)
            }
        }
    }

    suspend fun ensureEmployeesFromExistingDebts() = rebuildEmployeeIndex()
    suspend fun repairEmployeeIndex() = rebuildEmployeeIndex()

    private suspend fun rebuildEmployeeIndex() {
        val stored = employees.allForSync()
        val storedByIka = stored.mapNotNull { employee ->
            val ika = EmployeeEntity.normalizeIka(employee.amIka)
            if (ika.isBlank()) null else ika to employee
        }.groupBy({ it.first }, { it.second })

        // Κλειδί κατά προτίμηση ο ΑΜ ΙΚΑ· όταν λείπει, ο κωδικός μισθοδοσίας.
        // Απαιτώντας τον, όποιος δεν τον έδωσε καθαρά στο OCR έμενε χωρίς
        // καρτέλα και έλειπε και από το φύλλο των κοστών.
        val payrollByIka = debts.allForSync().asSequence()
            .filter { !it.deleted && it.kind.perPerson }
            .mapNotNull { debt ->
                val key = EmployeeEntity.keyFor(debt.amIka, debt.personCode, debt.personName)
                if (key.isBlank()) null else key to debt
            }.groupBy({ it.first }, { it.second })

        var hardDeletedDuplicates = 0
        storedByIka.forEach { (ika, rows) ->
            val survivor = rows.firstOrNull { it.id == ika } ?: rows.maxByOrNull { it.updatedAt } ?: return@forEach
            rows.filter { it.id != survivor.id }.forEach { employees.hardDelete(it.id); hardDeletedDuplicates++ }
            if (survivor.id != ika) {
                employees.hardDelete(survivor.id)
                employees.upsert(survivor.copy(id = ika, amIka = ika))
            }
        }

        val updates = payrollByIka.map { (ika, rows) ->
            val existing = storedByIka[ika]?.firstOrNull { it.id == ika }
                ?: storedByIka[ika]?.maxByOrNull { it.updatedAt }
                ?: stored.firstOrNull { it.id == ika }
            val latest = rows.maxByOrNull { it.updatedAt } ?: rows.first()
            val summary = existing?.payrollSummaryJson?.takeIf { it.isNotBlank() && it != "{}" }
                ?: ensureSnapshotPeriods("{}", rows, ika)

            EmployeeEntity(
                id = ika,
                // Το πεδίο μένει κενό όταν όντως δεν ξέρουμε τον ΑΜ ΙΚΑ, αντί
                // να γεμίσει με τον κωδικό και να περάσει για ΑΜ ΙΚΑ
                amIka = EmployeeEntity.normalizeIka(
                    rows.firstOrNull { it.amIka.isNotBlank() }?.amIka.orEmpty(),
                ).ifBlank { existing?.amIka.orEmpty() },
                name = rows.firstOrNull { it.personName.isNotBlank() }?.personName?.trim() ?: existing?.name.orEmpty(),
                alias = existing?.alias.orEmpty(),
                code = rows.firstOrNull { it.personCode.isNotBlank() }?.personCode?.trim() ?: existing?.code.orEmpty(),
                leftDay = existing?.leftDay,
                updatedAt = maxOf(latest.updatedAt, existing?.updatedAt ?: 0L),
                deleted = false,
                payrollSummaryJson = summary,
            )
        }.sortedBy { it.name.uppercase() }

        if (updates.isNotEmpty()) employees.upsertAll(updates)
        EmployeeAliasRegistry.refresh(employees.allForSync())
        DebugLog.log("employees", "employee DB rebuild complete · active payroll AM IKA=${updates.size} · preserved employees=${employees.allForSync().size} · hard-deleted duplicates=$hardDeletedDuplicates")
    }

    private fun ensureSnapshotPeriods(json: String, rows: List<DebtEntity>, ika: String): String {
        val root = runCatching { JSONObject(json) }.getOrElse { JSONObject() }
        rows.groupBy { it.periodYear to it.periodMonth }.forEach { (period, periodRows) ->
            val year = period.first
            val month = period.second
            if (year <= 0 || month !in 1..12) return@forEach
            val key = "%04d-%02d".format(year, month)
            if (root.has(key)) return@forEach
            root.put(key, JSONObject().apply {
                put("payable", periodRows.sumOf { it.amount })
                // Do not fabricate an insurance cost here. An imported payroll
                // without a complete snapshot is deliberately re-scanned by
                // payrollFileIdsNeedingSnapshot() on the next Drive sync.
                put("insuranceDays", PayrollInsuranceDaysStore.daysFor(appContext, ika, year, month))
            })
        }
        return root.toString()
    }

    suspend fun setPaid(id: String, paid: Boolean, day: Long? = null) {
        debts.markPaid(id, paid, if (paid) (day ?: System.currentTimeMillis()) else null, System.currentTimeMillis())
    }

    suspend fun delete(id: String) = delete(listOf(id))

    suspend fun delete(ids: Collection<String>) {
        val now = System.currentTimeMillis()
        ids.forEach { debts.softDelete(it, now) }
    }

    suspend fun deleteFromFile(source: String, driveFileId: String) {
        if (driveFileId.isBlank()) return
        val ids = debts.allForSync().filter { it.driveFileId == driveFileId && (source.isBlank() || it.source == source) }.map { it.id }
        delete(ids)
    }

    private fun normalizeByDueDate(debt: DebtEntity): DebtEntity {
        val resolvedDue = debt.dueDay ?: DebtEntity.defaultDue(debt.kind, debt.periodYear, debt.periodMonth) ?: return debt
        val dueDate = java.time.LocalDate.ofEpochDay(resolvedDue)
        return debt.copy(periodMonth = dueDate.monthValue, periodYear = dueDate.year, dueDay = resolvedDue)
    }
}
