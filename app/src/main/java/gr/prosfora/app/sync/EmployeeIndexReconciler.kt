package gr.prosfora.app.sync

import android.content.Context
import gr.prosfora.app.data.db.EmployeeAliasRegistry
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.data.db.ProsforaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Maintains the employee index without making employees dependent on debts.
 * One AM IKA = one employee card. Deleting a debt never deletes the employee.
 */
object EmployeeIndexReconciler {
    suspend fun rebuild(context: Context) = withContext(Dispatchers.IO) {
        val db = ProsforaDatabase.get(context.applicationContext)
        val employeeDao = db.employeeDao()
        val debtDao = db.debtDao()
        var stored = employeeDao.allForSync()
        val storedByIka = stored
            .mapNotNull { employee ->
                val ika = EmployeeEntity.normalizeIka(employee.amIka)
                if (ika.isBlank()) null else ika to employee
            }
            .groupBy({ it.first }, { it.second })

        storedByIka.forEach { (ika, rows) ->
            val survivor = rows.firstOrNull { it.id == ika }
                ?: rows.maxByOrNull { it.updatedAt }
                ?: return@forEach

            rows.filter { it.id != survivor.id }.forEach { duplicate ->
                employeeDao.hardDelete(duplicate.id)
            }

            if (survivor.id != ika) {
                employeeDao.hardDelete(survivor.id)
                employeeDao.upsert(survivor.copy(id = ika, amIka = ika))
            }
        }

        // IMPORTANT: reload after duplicate/id repair and after any preceding
        // payroll snapshot write. Otherwise rebuild() could take the old
        // payrollSummaryJson and overwrite a freshly recorded snapshot with {}.
        stored = employeeDao.allForSync()
        val currentByIka = stored
            .mapNotNull { employee ->
                val ika = EmployeeEntity.normalizeIka(employee.amIka)
                if (ika.isBlank()) null else ika to employee
            }
            .groupBy({ it.first }, { it.second })

        // Ο ΑΜ ΙΚΑ προτιμάται αλλά δεν απαιτείται: όποιος δεν τον έδωσε καθαρά
        // στο OCR έμενε χωρίς καρτέλα και έλειπε από το φύλλο των κοστών.
        val payrollByIka = debtDao.allForSync()
            .asSequence()
            .filter { !it.deleted && it.kind.perPerson }
            .mapNotNull { debt ->
                val key = EmployeeEntity.keyFor(debt.amIka, debt.personCode, debt.personName)
                if (key.isBlank()) null else key to debt
            }
            .groupBy({ it.first }, { it.second })

        val updates = payrollByIka.map { (ika, rows) ->
            val existing = currentByIka[ika]
                ?.firstOrNull { it.id == ika }
                ?: currentByIka[ika]?.maxByOrNull { it.updatedAt }
                ?: stored.firstOrNull { it.id == ika }
            val latest = rows.maxByOrNull { it.updatedAt } ?: rows.first()

            EmployeeEntity(
                id = ika,
                amIka = EmployeeEntity.normalizeIka(
                    rows.firstOrNull { it.amIka.isNotBlank() }?.amIka.orEmpty(),
                ).ifBlank { existing?.amIka.orEmpty() },
                name = rows.firstOrNull { it.personName.isNotBlank() }?.personName?.trim()
                    ?: existing?.name.orEmpty(),
                alias = existing?.alias.orEmpty(),
                code = rows.firstOrNull { it.personCode.isNotBlank() }?.personCode?.trim()
                    ?: existing?.code.orEmpty(),
                leftDay = existing?.leftDay,
                updatedAt = latest.updatedAt,
                deleted = false,
                // Preserve the CURRENT persisted payroll history. It is
                // independent from DebtEntity lifetime and must survive
                // rebuilds and debt deletion.
                payrollSummaryJson = existing?.payrollSummaryJson ?: "{}",
            )
        }

        if (updates.isNotEmpty()) employeeDao.upsertAll(updates)
        EmployeeAliasRegistry.refresh(employeeDao.allForSync())
    }
}
