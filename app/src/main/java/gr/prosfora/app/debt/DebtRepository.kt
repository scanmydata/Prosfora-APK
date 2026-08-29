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
        rememberPeople(merged)
        rememberPeople(debts.allForSync())
    }

    suspend fun ensureEmployeesFromExistingDebts() {
        rememberPeople(debts.allForSync())
    }

    /**
     * Κανόνας ταυτοποίησης εργαζομένου:
     *
     * 1. Αν υπάρχει ΑΜ ΙΚΑ, αυτό είναι το ΜΟΝΑΔΙΚΟ canonical key.
     * 2. Το όνομα και ο κωδικός χρησιμοποιούνται μόνο για να βρεθεί τυχόν
     *    παλιό/legacy employee record και να μεταφερθούν alias/στοιχεία.
     * 3. Δεν επιτρέπεται δεύτερη ενεργή καρτέλα με το ίδιο ΑΜ ΙΚΑ.
     * 4. Μόνο παλιές γραμμές χωρίς ΑΜ ΙΚΑ χρησιμοποιούν προσωρινό fallback key.
     */
    private suspend fun rememberPeople(items: List<DebtEntity>) {
        val payroll = items.filter {
            it.kind.perPerson && (
                it.personName.isNotBlank() ||
                    it.personCode.isNotBlank() ||
                    it.amIka.isNotBlank()
                )
        }
        if (payroll.isEmpty()) return

        val existing = employees.allForSync()
        val existingByIka = existing
            .filter { EmployeeEntity.normalizeIka(it.amIka).isNotBlank() }
            .groupBy { EmployeeEntity.normalizeIka(it.amIka) }
        val existingByFallback = existing
            .associateBy { employeeFallbackKey(it.code, it.name) }
        val existingByName = existing
            .filter { it.name.isNotBlank() }
            .groupBy { canonicalPersonName(it.name) }

        val duplicateIdsToHide = linkedSetOf<String>()

        val people = payroll
            .groupBy { employeeKey(it.amIka, it.personCode, it.personName) }
            .mapNotNull { (_, rows) ->
                val first = rows.firstOrNull() ?: return@mapNotNull null
                val ika = EmployeeEntity.normalizeIka(first.amIka)
                val name = rows.firstOrNull { it.personName.isNotBlank() }
                    ?.personName
                    ?.trim()
                    .orEmpty()
                val code = rows.firstOrNull { it.personCode.isNotBlank() }
                    ?.personCode
                    ?.trim()
                    .orEmpty()

                if (ika.isBlank() && name.isBlank() && code.isBlank()) return@mapNotNull null

                val canonicalCandidates = if (ika.isNotBlank()) {
                    existingByIka[ika].orEmpty()
                } else {
                    emptyList()
                }
                val old = canonicalCandidates.firstOrNull()
                    ?: existingByFallback[employeeFallbackKey(code, name)]
                    ?: existingByName[canonicalPersonName(name)]?.firstOrNull()

                val canonicalId = if (ika.isNotBlank()) {
                    EmployeeEntity.idForAmIka(ika)
                } else {
                    old?.id ?: fallbackEmployeeId(code, name)
                }

                // Αν υπήρχε legacy/fallback record για το ίδιο άτομο και τώρα
                // έχουμε ΑΜ ΙΚΑ, κρατάμε τα στοιχεία του αλλά κρύβουμε το παλιό
                // record ώστε η οθόνη να έχει ΜΙΑ μόνο καρτέλα.
                if (old != null && old.id != canonicalId) {
                    duplicateIdsToHide += old.id
                }
                canonicalCandidates.drop(1).forEach { duplicateIdsToHide += it.id }

                EmployeeEntity(
                    id = canonicalId,
                    amIka = ika.ifBlank { old?.amIka.orEmpty() },
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
        duplicateIdsToHide
            .filter { id -> people.none { it.id == id } }
            .forEach { id -> employees.softDelete(id, System.currentTimeMillis()) }

        DebugLog.log(
            "employees",
            "canonical employee index updated · cards=${people.size} · by AM IKA=${people.count { it.amIka.isNotBlank() }} · hidden duplicates=${duplicateIdsToHide.size}",
        )
    }

    private fun employeeKey(amIka: String, code: String, name: String): String {
        val ika = EmployeeEntity.normalizeIka(amIka)
        return if (ika.isNotBlank()) {
            "ika:$ika"
        } else {
            employeeFallbackKey(code, name)
        }
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
        rememberPeople(debts.allForSync())
        return victims.size
    }

    suspend fun importedFileIds(): Set<String> = debts.importedFileIds().toSet()

    private fun normalizeByDueDate(debt: DebtEntity): DebtEntity {
        val due = debt.dueDay?.let(LocalDate::ofEpochDay) ?: return debt
        return debt.copy(periodMonth = due.monthValue, periodYear = due.year)
    }
}
