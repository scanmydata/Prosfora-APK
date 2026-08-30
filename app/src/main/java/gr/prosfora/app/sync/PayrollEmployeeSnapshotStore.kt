package gr.prosfora.app.sync

import android.content.Context
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.data.db.ProsforaDatabase
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persists the payroll snapshot independently from DebtEntity lifetime.
 * The payroll PDF is the source of truth for payable amount, insurance days
 * and total employee+employer insurance cost.
 */
object PayrollEmployeeSnapshotStore {
    suspend fun record(context: Context, ocrText: String, debts: List<DebtEntity>) = withContext(Dispatchers.IO) {
        val payroll = debts.filter { it.kind.perPerson && EmployeeEntity.normalizeIka(it.amIka).isNotBlank() }
        if (payroll.isEmpty() || ocrText.isBlank()) return@withContext

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

                val key = "%04d-%02d".format(year, month)
                val previous = current.optJSONObject(key)
                val metrics = PayrollMetricsExtractor.find(ocrText, periodRows.first())

                // The PDF is authoritative. If OCR was incomplete for one metric,
                // preserve the previous valid value instead of replacing it with 0.
                val payable = metrics.payable
                    ?: previous?.optDouble("payable", Double.NaN)?.takeUnless { it.isNaN() }
                    ?: periodRows.sumOf { it.amount }
                val insuranceCost = metrics.insuranceCost
                    ?: previous?.optDouble("insuranceCost", Double.NaN)?.takeUnless { it.isNaN() }
                    ?: 0.0
                val insuranceDays = metrics.insuranceDays
                    ?: previous?.optInt("insuranceDays", 0)?.takeIf { it > 0 }
                    ?: PayrollInsuranceDaysStore.daysFor(context.applicationContext, ika, year, month)

                current.put(
                    key,
                    JSONObject().apply {
                        put("payable", payable)
                        put("insuranceCost", insuranceCost)
                        put("insuranceDays", insuranceDays)
                        if (previous?.has("source") == true) put("source", previous.optString("source"))
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
        private val rowStart = Regex("""^\s*\d{1,3}\s+([0-9]{2,6})\b""")
        private val moneyToken = Regex("(?<!\\d)(?:[0-9][0-9.]*,[0-9]{2}|[0-9][0-9,]*\\.[0-9]{2})(?!\\d)")
        private val daysPatterns = listOf(
            Regex("""\bΤΑ\s*[:=\-]?\s*(\d{1,2})(?:[.,]\d+)?\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d{1,2})(?:[.,]\d+)?\s+ΤΑ\b""", RegexOption.IGNORE_CASE),
        )

        data class Metrics(val payable: Double?, val insuranceCost: Double?, val insuranceDays: Int?)
        private data class Candidate(val lineIndex: Int, val numbers: List<Double>)

        private fun parseMoney(raw: String): Double? {
            val token = raw.trim()
            return when {
                token.contains(',') -> token.replace(".", "").replace(',', '.').toDoubleOrNull()
                else -> token.replace(",", "").toDoubleOrNull()
            }
        }

        fun find(text: String, debt: DebtEntity): Metrics {
            val code = debt.personCode.trim()
            if (text.isBlank() || code.isBlank()) return Metrics(null, null, null)

            val normalizedCode = code.trimStart('0').ifBlank { code }
            val lines = text.lines()
            val start = lines.indexOfFirst { raw ->
                val line = raw.removePrefix("\uFEFF").trim()
                val row = rowStart.find(line) ?: return@indexOfFirst false
                val rowCode = row.groupValues[1].trimStart('0').ifBlank { row.groupValues[1] }
                rowCode == normalizedCode
            }
            if (start < 0) return Metrics(null, null, null)

            val end = (start + 1 until lines.size)
                .firstOrNull { rowStart.containsMatchIn(lines[it].removePrefix("\uFEFF").trim()) }
                ?: lines.size
            val block = lines.subList(start, end)

            val insuranceDays = block.asSequence()
                .flatMap { line -> daysPatterns.asSequence().mapNotNull { it.find(line)?.groupValues?.get(1) } }
                .mapNotNull { it.replace(',', '.').toDoubleOrNull()?.toInt() }
                .firstOrNull { it in 0..31 }

            val candidates = block.mapIndexedNotNull { index, raw ->
                val numbers = moneyToken.findAll(raw).mapNotNull { parseMoney(it.value) }.toList()
                numbers.takeIf { it.size >= 8 }?.let { Candidate(start + index, it) }
            }

            // In normal payroll PDFs the employee total line is the widest
            // numeric line in the employee block and its last amount is payable.
            // Prefer the last candidate on ties so wrapped OCR lines still work.
            val total = candidates.maxWithOrNull(
                compareBy<Candidate> { it.numbers.size }.thenBy { it.lineIndex },
            )

            if (total != null) {
                val numbers = total.numbers
                val insuranceCost = when {
                    numbers.size >= 4 -> numbers[3]
                    numbers.size >= 3 -> numbers[1] + numbers[2]
                    else -> null
                }
                return Metrics(
                    payable = numbers.lastOrNull(),
                    insuranceCost = insuranceCost,
                    insuranceDays = insuranceDays,
                )
            }

            // Some PDF text layers wrap the total line. Combine up to three
            // adjacent numeric fragments as a tolerant fallback.
            val fragments = block.mapIndexedNotNull { index, raw ->
                val numbers = moneyToken.findAll(raw).mapNotNull { parseMoney(it.value) }.toList()
                numbers.takeIf { it.isNotEmpty() }?.let { Candidate(start + index, it) }
            }

            var best: Candidate? = null
            for (i in fragments.indices) {
                val combined = mutableListOf<Double>()
                for (j in i until minOf(i + 3, fragments.size)) {
                    if (j > i && fragments[j].lineIndex != fragments[j - 1].lineIndex + 1) break
                    combined += fragments[j].numbers
                    if (combined.size >= 8) {
                        best = Candidate(fragments[j].lineIndex, combined.toList())
                        break
                    }
                }
            }

            best?.let {
                val numbers = it.numbers
                val insuranceCost = when {
                    numbers.size >= 4 -> numbers[3]
                    numbers.size >= 3 -> numbers[1] + numbers[2]
                    else -> null
                }
                return Metrics(numbers.lastOrNull(), insuranceCost, insuranceDays)
            }

            return Metrics(null, null, insuranceDays)
        }
    }
}
