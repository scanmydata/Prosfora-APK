package gr.prosfora.app.sync

import android.content.Context
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.data.db.ProsforaDatabase
import org.json.JSONObject

/**
 * Persistent payroll history owned by the employee card.
 * Key: AM IKA + payment due year/month.
 * The snapshot is updated only when a payroll file is imported and is not
 * affected when the corresponding debt rows are later deleted.
 */
object PayrollEmployeeSnapshotStore {
    fun record(context: Context, ocrText: String, debts: List<DebtEntity>) {
        val payroll = debts.filter { it.kind.perPerson && EmployeeEntity.normalizeIka(it.amIka).isNotBlank() }
        if (payroll.isEmpty()) return

        val db = ProsforaDatabase.get(context.applicationContext)
        val byEmployee = payroll.groupBy { EmployeeEntity.normalizeIka(it.amIka) }
        val people = db.employeeDao().allForSync().associateBy { EmployeeEntity.normalizeIka(it.amIka) }

        byEmployee.forEach { (ika, rows) ->
            val old = people[ika]
            val current = runCatching { JSONObject(old?.payrollSummaryJson ?: "{}") }.getOrElse { JSONObject() }
            rows.groupBy { it.periodYear to it.periodMonth }.forEach { (periodKey, periodRows) ->
                val year = periodKey.first
                val month = periodKey.second
                if (year <= 0 || month !in 1..12) return@forEach

                val jsonKey = "%04d-%02d".format(year, month)
                val period = JSONObject().apply {
                    put("payable", periodRows.sumOf { it.amount })
                    put("insuranceCost", PayrollInsuranceCostExtractor.find(ocrText, periodRows.first()))
                    put("insuranceDays", PayrollInsuranceDaysStore.daysFor(context, ika, year, month))
                }
                current.put(jsonKey, period)
            }

            val employee = old ?: EmployeeEntity(
                id = ika,
                amIka = ika,
                name = rows.firstOrNull { it.personName.isNotBlank() }?.personName?.trim().orEmpty(),
                code = rows.firstOrNull { it.personCode.isNotBlank() }?.personCode?.trim().orEmpty(),
            )
            db.employeeDao().upsert(
                employee.copy(
                    id = ika,
                    amIka = ika,
                    name = rows.firstOrNull { it.personName.isNotBlank() }?.personName?.trim()?.ifBlank { employee.name } ?: employee.name,
                    code = rows.firstOrNull { it.personCode.isNotBlank() }?.personCode?.trim()?.ifBlank { employee.code } ?: employee.code,
                    payrollSummaryJson = current.toString(),
                    updatedAt = System.currentTimeMillis(),
                    deleted = false,
                ),
            )
        }
    }

    data class Totals(
        val payable: Double = 0.0,
        val insuranceCost: Double = 0.0,
        val insuranceDays: Int = 0,
    )

    fun totals(employee: EmployeeEntity, year: Int? = null): Totals {
        val root = runCatching { JSONObject(employee.payrollSummaryJson) }.getOrElse { JSONObject() }
        var payable = 0.0
        var cost = 0.0
        var days = 0
        root.keys().forEach { key ->
            val y = key.substringBefore('-').toIntOrNull() ?: return@forEach
            if (year != null && y != year) return@forEach
            val row = root.optJSONObject(key) ?: return@forEach
            payable += row.optDouble("payable", 0.0)
            cost += row.optDouble("insuranceCost", 0.0)
            days += row.optInt("insuranceDays", 0)
        }
        return Totals(payable, cost, days)
    }

    private object PayrollInsuranceCostExtractor {
        private val amountRegex = Regex("[0-9][0-9.]*,[0-9]{2}")
        private val rowHeader = Regex("""^\s*\d{1,3}\s+[A-ZΑ-Ω0-9]{2,6}\s+""")

        /**
         * Extracts the actual payroll column named «Κόστος».
         * In the sample payroll summary the numeric columns are:
         * ... Κρατήσεις, Καθ.Αποδ., Κόστος, Προκ/λή, Πληρωτέο.
         * Therefore «Κόστος» is numeric index 9 (zero-based).
         */
        fun find(text: String, debt: DebtEntity): Double {
            if (text.isBlank() || debt.personCode.isBlank()) return 0.0
            val wanted = debt.personName.trim().split(Regex("\\s+")).filter { it.length >= 2 }
            val code = debt.personCode.trimStart('0').ifBlank { debt.personCode.trim() }
            val lines = text.lines()
            val start = lines.indexOfFirst { line ->
                val compact = line.uppercase()
                compact.contains(code) && wanted.all { token -> compact.contains(token.uppercase()) }
            }
            if (start < 0) return 0.0

            for (index in start + 1 until lines.size) {
                val line = lines[index].trim()
                if (line.isBlank()) continue
                if (rowHeader.containsMatchIn(line)) break
                if (line.any { it.isLetter() }) continue
                val numbers = line.split(Regex("\\s+")).mapNotNull { token ->
                    amountRegex.matchEntire(token)?.value
                        ?.replace(".", "")
                        ?.replace(',', '.')
                        ?.toDoubleOrNull()
                }
                if (numbers.size >= 10) return numbers[9]
            }
            return 0.0
        }
    }
}
