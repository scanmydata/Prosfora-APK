package gr.prosfora.app.sync

import android.content.Context
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.data.db.ProsforaDatabase
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object PayrollEmployeeSnapshotStore {
    suspend fun record(context: Context, ocrText: String, debts: List<DebtEntity>) = withContext(Dispatchers.IO) {
        val payroll = debts.filter { it.kind.perPerson && EmployeeEntity.normalizeIka(it.amIka).isNotBlank() }
        if (payroll.isEmpty()) return@withContext

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

                val metrics = PayrollMetricsExtractor.find(ocrText, periodRows.first())
                current.put(
                    "%04d-%02d".format(year, month),
                    JSONObject().apply {
                        put("payable", periodRows.sumOf { it.amount })
                        put("insuranceCost", metrics.insuranceCost)
                        put("insuranceDays", metrics.insuranceDays)
                    },
                )
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

    data class Totals(val payable: Double = 0.0, val insuranceCost: Double = 0.0, val insuranceDays: Int = 0)
    data class Monthly(val year: Int, val month: Int, val payable: Double, val insuranceCost: Double, val insuranceDays: Int)

    fun history(employee: EmployeeEntity): List<Monthly> {
        val root = runCatching { JSONObject(employee.payrollSummaryJson) }.getOrElse { JSONObject() }
        return buildList {
            root.keys().forEach { key ->
                val parts = key.split('-')
                if (parts.size != 2) return@forEach
                val year = parts[0].toIntOrNull() ?: return@forEach
                val month = parts[1].toIntOrNull() ?: return@forEach
                if (year <= 0 || month !in 1..12) return@forEach
                val row = root.optJSONObject(key) ?: return@forEach
                add(Monthly(year, month, row.optDouble("payable", 0.0), row.optDouble("insuranceCost", 0.0), row.optInt("insuranceDays", 0)))
            }
        }.sortedWith(compareByDescending<Monthly> { it.year }.thenByDescending { it.month })
    }

    fun totals(employee: EmployeeEntity, year: Int? = null): Totals {
        val history = history(employee).filter { year == null || it.year == year }
        return Totals(history.sumOf { it.payable }, history.sumOf { it.insuranceCost }, history.sumOf { it.insuranceDays })
    }

    private object PayrollMetricsExtractor {
        private val employeeLine = Regex("""^\s*\d{1,3}\s+[0-9]{2,6}\s+.+$""")
        private val regularTaLine = Regex("""^\s*ΤΑ\s+(\d+(?:,\d+)?)\s+""", RegexOption.IGNORE_CASE)
        private val moneyToken = Regex("[0-9][0-9.]*,[0-9]{2}")
        private val rowStart = Regex("""^\s*\d{1,3}\s+[0-9]{2,6}\s+""")

        data class Metrics(val insuranceDays: Int, val insuranceCost: Double)

        fun find(text: String, debt: DebtEntity): Metrics {
            val ika = EmployeeEntity.normalizeIka(debt.amIka)
            val code = debt.personCode.trim()
            if (text.isBlank() || ika.isBlank() || code.isBlank()) return Metrics(0, 0.0)

            val normalizedCode = code.trimStart('0').ifBlank { code }
            val lines = text.lines()
            val start = lines.indexOfFirst { raw ->
                val line = raw.trim()
                if (!employeeLine.matches(line)) return@indexOfFirst false
                val tokens = line.split(Regex("\\s+"), limit = 3)
                if (tokens.size < 3) return@indexOfFirst false
                val rowCode = tokens[1].trimStart('0').ifBlank { tokens[1] }
                rowCode == normalizedCode && line.contains(ika)
            }
            if (start < 0) return Metrics(0, 0.0)

            var days = 0
            var cost = 0.0
            var i = start + 1
            while (i < lines.size) {
                val line = lines[i].trim()
                if (line.isBlank()) { i++; continue }
                if (i > start + 1 && rowStart.containsMatchIn(line)) break

                val ta = regularTaLine.find(line)
                if (ta != null) {
                    days = ta.groupValues[1].replace(',', '.').toDoubleOrNull()?.toInt() ?: 0
                    var j = i + 1
                    while (j < lines.size) {
                        val next = lines[j].trim()
                        if (next.isBlank()) { j++; continue }
                        if (rowStart.containsMatchIn(next)) break
                        val numbers = next.split(Regex("\\s+")).mapNotNull { token ->
                            moneyToken.matchEntire(token)?.value?.replace(".", "")?.replace(',', '.')?.toDoubleOrNull()
                        }
                        if (numbers.size == 12) {
                            cost = numbers[3]
                            break
                        }
                        j++
                    }
                    break
                }
                i++
            }
            return Metrics(days, cost)
        }
    }
}
