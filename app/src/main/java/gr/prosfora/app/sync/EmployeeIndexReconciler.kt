package gr.prosfora.app.sync

import android.content.Context
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.data.db.ProsforaDatabase
import gr.prosfora.app.debt.DebtRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Οι εργαζόμενοι δεν είναι δεύτερη πηγή δεδομένων.
 * Δημιουργούνται αποκλειστικά από τις ενεργές PAYROLL/PAYROLL_BONUS οφειλές.
 *
 * Έτσι ισχύει ο ίδιος κανόνας με τη μισθοδοσία:
 * ένα ΑΜ ΙΚΑ -> μία καρτέλα εργαζομένου.
 */
object EmployeeIndexReconciler {
    suspend fun rebuild(context: Context) = withContext(Dispatchers.IO) {
        val db = ProsforaDatabase.get(context.applicationContext)
        val debts = db.debtDao().allForSync()
        val oldEmployees = db.employeeDao().allForSync()
            .mapNotNull { employee ->
                val ika = EmployeeEntity.normalizeIka(employee.amIka)
                if (ika.isBlank()) null else ika to employee
            }
            .groupBy({ it.first }, { it.second })

        val people = debts
            .filter { !it.deleted && it.kind.perPerson }
            .mapNotNull { debt ->
                val ika = EmployeeEntity.normalizeIka(debt.amIka)
                if (ika.isBlank()) null else ika to debt
            }
            .groupBy({ it.first }, { it.second })
            .map { (ika, rows) ->
                val latest = rows.maxByOrNull { it.updatedAt } ?: rows.first()
                val old = oldEmployees[ika]
                    ?.maxByOrNull { it.updatedAt }

                EmployeeEntity(
                    id = ika,
                    amIka = ika,
                    name = rows.firstOrNull { it.personName.isNotBlank() }?.personName?.trim()
                        ?: old?.name.orEmpty(),
                    alias = old?.alias.orEmpty(),
                    code = rows.firstOrNull { it.personCode.isNotBlank() }?.personCode?.trim()
                        ?: old?.code.orEmpty(),
                    leftDay = old?.leftDay,
                    updatedAt = latest.updatedAt,
                    deleted = false,
                )
            }
            .sortedBy { it.name.uppercase() }

        // Η καρτέλα εργαζομένων δεν είναι ανεξάρτητη βάση. Καθαρίζεται
        // πραγματικά και ξαναχτίζεται από τις μισθοδοτικές οφειλές.
        db.openHelper.writableDatabase.execSQL("DELETE FROM employees")
        if (people.isNotEmpty()) {
            db.employeeDao().upsertAll(people)
        }
    }
}
