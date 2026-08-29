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
        val people = db.employeeDao()
            .allForSync()
            .associateBy { EmployeeEntity.normalizeIka(it.amIka) }

        byEmployee.forEach { (ika, rows) ->
            val old = people[ika]
            val current = runCatching {
                JSONObject(old?.payrollSummaryJson ?: "{}")
            }.getOrElse { JSONObject() }

            rows.groupBy { it.periodYear to it.periodMonth }.forEach { (periodKey, periodRows) ->
                val year = periodKey.first
                val month = periodKey.second
                if (year <= 0 || month !in 1..12) return@forEach

                val metrics = PayrollMetricsExtractor.find(ocrText, periodRows.first())
                val jsonKey = "%04d-%02d".format(year, month)
                val period = JSONObject().apply {
                    put("payable", periodRows.sumOf { it.amount })
                    put("insuranceCost", metrics.insuranceCost)
                    put("insuranceDays", metrics.insuranceDays)
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
                    name = rows.firstOrNull { it.personName.isNotBlank() }
                        ?.personName
                        ?.trim()
                        ?.ifBlank { employee.name }
                        ?: employee.name,
                    code = rows.firstOrNull { it.personCode.isNotBlank() }
                        ?.personCode
                        ?.trim()
                        ?.ifBlank { employee.code }
                        ?: employee.code,
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
        val root = runCatching { JSONObject(employee.payrollSummaryJson) }
            .getOrElse { JSONObject() }

        return buildList {
            root.keys().forEach { key ->
                val parts = key.split('-')
                if (parts.size != 2) return@forEach

                val year = parts[0].toIntOrNull() ?: return@forEach
                val month = parts[1].toIntOrNull() ?: return@forEach
                if (year <= 0 || month !in 1..12) return@forEach

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
        }.sortedWith(
            compareByDescending<Monthly> { it.year }
                .thenByDescending { it.month },
        )
    }

    fun totals(employee: EmployeeEntity, year: Int? = null): Totals {
        val history = history(employee).filter { year == null || it.year == year }
        return Totals(
            payable = history.sumOf { it.payable },
            insuranceCost = history.sumOf { it.insuranceCost },
            insuranceDays = history.sumOf { it.insuranceDays },
        )
    }

    /**
     * Extracts the payroll values from the employee block of the OCR text.
     *
     * Business rules:
     * - Insurance days = the number immediately after the regular "ΤΑ" row marker.
     *   Bonus rows (ΔΧ/ΕΑ/ΑΛ/ΜΛ) do not add insurance days.
     * - Insurance cost = the 4th monetary value of the payroll totals row.
     *   In the supplied payroll statement this is 118,86 for BUTT HURARA.
     */
    private object PayrollMetricsExtractor {
        private val employeeHeader = Regex(
            """^\s*\d{1,3}\s+[A-ZΑ-Ω0-9]{2,8}\s+.+$""",
        )
        private val regularRow = Regex(
            """^\s*ΤΑ\s+(\d+(?:,\d+)?)\s+""",
            RegexOption.IGNORE_CASE,
        )
        private val moneyToken = Regex("[0-9][0-9.]*,[0-9]{2}")

        data class Metrics(
            val insuranceDays: Int,
            val insuranceCost: Double,
        )

        fun find(text: String, debt: DebtEntity): Metrics {
            if (text.isBlank()) return Metrics(0, 0.0)

            val wantedName = debt.personName
                .trim()
                .split(Regex("\\s+"))
                .filter { it.length >= 2 }
                .map { it.uppercase() }
            val wantedCode = debt.personCode.trim()

            if (wantedName.isEmpty() && wantedCode.isBlank()) {
                return Metrics(0, 0.0)
            }

            val lines = text.lines()
            var employeeStart = -1

            lines.forEachIndexed { index, raw ->
                if (employeeStart >= 0) return@forEachIndexed
                val line = raw.trim()
                if (!employeeHeader.matches(line)) return@forEachIndexed

                val upper = line.uppercase()
                val codeMatch = wantedCode.isNotBlank() && (
                    upper.contains(" ${wantedCode.uppercase()} ") ||
                        upper.endsWith(" ${wantedCode.uppercase()}")
                    )
                val nameMatch = wantedName.isNotEmpty() && wantedName.all { upper.contains(it) }
                if (codeMatch || nameMatch) {
                    employeeStart = index
                }
            }

            if (employeeStart < 0) return Metrics(0, 0.0)

            var insuranceDays = 0
            var insuranceCost = 0.0
            var index = employeeStart + 1

            while (index < lines.size) {
                val line = lines[index].trim()
                if (line.isBlank()) {
                    index++
                    continue
                }

                // Reached the next employee block.
                if (index > employeeStart + 1 && employeeHeader.matches(line)) break

                val regular = regularRow.find(line)
                if (regular != null) {
                    val rawDays = regular.groupValues[1]
                    val days = rawDays.replace(',', '.')
                        .toDoubleOrNull()
                        ?.toInt()
                        ?: 0
                    insuranceDays += days

                    // Search forward inside this employee row until the totals line.
                    // The TEKA detail row has 4 numbers, while the payroll totals row has 12.
                    var summaryIndex = index + 1
                    while (summaryIndex < lines.size) {
                        val summary = lines[summaryIndex].trim()
                        if (summary.isBlank()) {
                            summaryIndex++
                            continue
                        }
                        if (employeeHeader.matches(summary)) break

                        val numbers = summary
                            .split(Regex("\\s+"))
                            .mapNotNull { token ->
                                moneyToken.matchEntire(token)
                                    ?.value
                                    ?.replace(".", "")
                                    ?.replace(',', '.')
                                    ?.toDoubleOrNull()
                            }

                        if (numbers.size >= 12) {
                            // 4th value = total insurance contributions.
                            insuranceCost += numbers[3]
                            break
                        }
                        summaryIndex++
                    }
                }

                index++
            }

            return Metrics(
                insuranceDays = insuranceDays,
                insuranceCost = insuranceCost,
            )
        }
    }
}
