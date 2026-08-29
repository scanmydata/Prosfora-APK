package gr.prosfora.app.sync

import android.content.Context
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.data.db.ProsforaDatabase
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persistent payroll history owned by the employee card.
 * Key: AM IKA + payment due year/month.
 * The snapshot is updated only when a payroll file is imported and is not
 * affected when the corresponding debt rows are later deleted.
 */
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

                val jsonKey = "%04d-%02d".format(year, month)
                val period = JSONObject().apply {
                    put("payable", periodRows.sumOf { it.amount })
                    put("insuranceCost", PayrollInsuranceCostExtractor.find(ocrText, periodRows.first()))
                    put("insuranceDays", PayrollInsuranceDaysExtractor.find(ocrText, periodRows))
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

    data class Monthly(
        val year: Int,
        val month: Int,
        val payable: Double,
        val insuranceCost: Double,
        val insuranceDays: Int,
    )

    fun history(employee: EmployeeEntity): List<Monthly> {
        val root = runCatching { JSONObject(employee.payrollSummaryJson) }.getOrElse { JSONObject() }
        return buildList {
            root.keys().forEach { key ->
                val parts = key.split('-')
                if (parts.size != 2) return@forEach
                val year = parts[0].toIntOrNull() ?: return@forEach
                val month = parts[1].toIntOrNull() ?: return@forEach
                if (month !in 1..12 || year <= 0) return@forEach
                val row = root.optJSONObject(key) ?: return@forEach
                add(
                    Monthly(
                        year = year,
                        month = month,
                        payable = row.optDouble("payable", 0.0),
                        insuranceCost = row.optDouble("insuranceCost", 0.0),
                        insuranceDays = row.optInt("insuranceDays", 0),
                    ),
                )
            }
        }.sortedWith(compareByDescending<Monthly> { it.year }.thenByDescending { it.month })
    }

    fun totals(employee: EmployeeEntity, year: Int? = null): Totals {
        val history = history(employee).filter { year == null || it.year == year }
        return Totals(
            payable = history.sumOf { it.payable },
            insuranceCost = history.sumOf { it.insuranceCost },
            insuranceDays = history.sumOf { it.insuranceDays },
        )
    }

    private object PayrollInsuranceDaysExtractor {
        private val header = Regex("""^\\s*\\d{1,3}\\s+[A-ZΑ-Ω0-9]{2,6}\\s+(.+)$""")
        private val paymentCode = Regex("""\\b(ΤΑ|ΔΧ|ΕΑ|ΑΛ|ΜΛ)\\s+(\\d+(?:,\\d+)?)\\s+\\d+(?:,\\d+)?\\s+""")
        private val genericAfterCode = Regex("""\\b[A-ZΑ-Ω0-9-]{2,6}\\s+(\\d+(?:,\\d+)?)\\s+\\d+(?:,\\d+)?\\s+""")

        fun find(text: String, debts: List<DebtEntity>): Int {
            if (text.isBlank() || debts.isEmpty()) return 0
            val name = debts.firstOrNull { it.personName.isNotBlank() }?.personName?.trim().orEmpty()
            val code = debts.firstOrNull { it.personCode.isNotBlank() }?.personCode?.trim().orEmpty()
            if (name.isBlank() && code.isBlank()) return 0

            val wanted = name.split(Regex("\\s+")).filter { it.length >= 2 }.map { it.uppercase() }
            val lines = text.lines()
            val candidates = mutableListOf<Pair<String, Int>>()

            lines.forEachIndexed { index, raw ->
                val line = raw.trim()
                if (line.isBlank()) return@forEachIndexed
                val compact = line.uppercase()
                val codeMatches = code.isNotBlank() && compact.contains(" $code ")
                val nameMatches = wanted.isNotEmpty() && wanted.all { compact.contains(it) }
                if (!codeMatches && !nameMatches) return@forEachIndexed
                if (!header.matches(line)) return@forEachIndexed

                val regular = paymentCode.find(line)
                val generic = genericAfterCode.find(line)
                val match = regular ?: generic
                if (match != null) {
                    val marker = regular?.groupValues?.getOrNull(1).orEmpty()
                    val rawDays = match.groupValues[match.groupValues.lastIndex]
                    val days = rawDays.replace(',', '.').toDoubleOrNull()?.toInt() ?: 0
                    candidates += marker to days
                    if (marker == "ΤΑ") return@forEachIndexed
                }

                // Keep searching later lines in case the employee has multiple payroll rows.
                if (index > lines.size) return@forEachIndexed
            }

            candidates.firstOrNull { it.first == "ΤΑ" }?.second
                ?: candidates.maxOfOrNull { it.second }
                ?: 0
        }
    }

    private object PayrollInsuranceCostExtractor {
        private val amountRegex = Regex("[0-9][0-9.]*,[0-9]{2}")
        private val rowHeader = Regex("""^\s*\d{1,3}\s+[A-ZΑ-Ω0-9]{2,6}\s+""")

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
                // Payroll totals row: [gross, employee contributions, employer contributions,
                // total insurance cost, ... , net payable, total employer cost, ...].
                if (numbers.size >= 4) return numbers[3]
            }
            return 0.0
        }
    }
}
