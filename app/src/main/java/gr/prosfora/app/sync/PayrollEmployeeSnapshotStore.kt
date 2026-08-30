package gr.prosfora.app.sync

import android.content.Context
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.data.db.ProsforaDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Canonical monthly payroll history. */
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
            rows.groupBy { it.periodYear to it.periodMonth }.forEach { (period, periodRows) ->
                val year = period.first
                val month = period.second
                if (year <= 0 || month !in 1..12) return@forEach
                val key = "%04d-%02d".format(year, month)
                val previous = current.optJSONObject(key)
                val extracted = PayrollMetricsExtractor.aggregate(ocrText, periodRows)
                val payable = periodRows.sumOf { it.amount }
                val insuranceCost = extracted.insuranceCost
                    ?: previous?.optDouble("insuranceCost", Double.NaN)?.takeUnless { it.isNaN() }
                    ?: 0.0
                val insuranceDays = extracted.insuranceDays
                    ?: previous?.optInt("insuranceDays", 0)?.takeIf { it > 0 }
                    ?: PayrollInsuranceDaysStore.daysFor(context.applicationContext, ika, year, month)
                current.put(key, JSONObject().apply {
                    put("payable", payable)
                    put("insuranceCost", insuranceCost)
                    put("insuranceDays", insuranceDays)
                    previous?.optString("source")?.takeIf { it.isNotBlank() }?.let { put("source", it) }
                })
            }
            val employee = old ?: EmployeeEntity(
                id = ika,
                amIka = ika,
                name = rows.firstOrNull { it.personName.isNotBlank() }?.personName?.trim().orEmpty(),
                code = rows.firstOrNull { it.personCode.isNotBlank() }?.personCode?.trim().orEmpty(),
            )
            db.employeeDao().upsert(employee.copy(
                id = ika,
                amIka = ika,
                name = rows.firstOrNull { it.personName.isNotBlank() }?.personName?.trim()?.ifBlank { employee.name } ?: employee.name,
                code = rows.firstOrNull { it.personCode.isNotBlank() }?.personCode?.trim()?.ifBlank { employee.code } ?: employee.code,
                payrollSummaryJson = current.toString(),
                updatedAt = System.currentTimeMillis(),
                deleted = false,
            ))
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
        val rows = history(employee).filter { year == null || it.year == year }
        return Totals(rows.sumOf { it.payable }, rows.sumOf { it.insuranceCost }, rows.sumOf { it.insuranceDays })
    }

    private object PayrollMetricsExtractor {
        private val rowStart = Regex("""^\s*\d{1,3}\s+([0-9]{2,6})\b""")
        private val moneyToken = Regex("""(?<!\d)(?:[0-9][0-9.]*,[0-9]{2}|[0-9][0-9,]*\.[0-9]{2})(?!\d)""")
        private val daysPatterns = listOf(
            Regex("""\bΤΑ\s*[:=\-]?\s*(\d{1,2})(?:[.,]\d+)?\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d{1,2})(?:[.,]\d+)?\s+ΤΑ\b""", RegexOption.IGNORE_CASE),
        )
        data class Metrics(val insuranceCost: Double?, val insuranceDays: Int?)
        private data class Candidate(val lineIndex: Int, val numbers: List<Double>)

        private fun parseMoney(raw: String): Double? = if (raw.contains(',')) raw.replace(".", "").replace(',', '.').toDoubleOrNull() else raw.replace(",", "").toDoubleOrNull()

        fun aggregate(text: String, debts: List<DebtEntity>): Metrics {
            if (text.isBlank() || debts.isEmpty()) return Metrics(null, null)
            val occurrenceByCode = mutableMapOf<String, Int>()
            var insuranceSum = 0.0
            var insuranceFound = false
            var maxDays: Int? = null
            debts.forEach { debt ->
                val code = debt.personCode.trim()
                if (code.isBlank()) return@forEach
                val normalized = code.trimStart('0').ifBlank { code }
                val occurrence = occurrenceByCode.getOrDefault(normalized, 0)
                occurrenceByCode[normalized] = occurrence + 1
                val row = find(text, debt, occurrence)
                row.insuranceCost?.let { insuranceSum += it; insuranceFound = true }
                row.insuranceDays?.let { maxDays = maxDays?.let { old -> maxOf(old, it) } ?: it }
            }
            return Metrics(insuranceSum.takeIf { insuranceFound }, maxDays)
        }

        private fun find(text: String, debt: DebtEntity, occurrence: Int): Metrics {
            val code = debt.personCode.trim()
            if (code.isBlank()) return Metrics(null, null)
            val normalized = code.trimStart('0').ifBlank { code }
            val lines = text.lines()
            var seen = 0
            var start = -1
            for (i in lines.indices) {
                val line = lines[i].removePrefix("\uFEFF").trim()
                val row = rowStart.find(line) ?: continue
                val rowCode = row.groupValues[1].trimStart('0').ifBlank { row.groupValues[1] }
                if (rowCode == normalized) {
                    if (seen == occurrence) { start = i; break }
                    seen++
                }
            }
            if (start < 0) return Metrics(null, null)
            val end = (start + 1 until lines.size).firstOrNull { rowStart.containsMatchIn(lines[it].removePrefix("\uFEFF").trim()) } ?: lines.size
            val block = lines.subList(start, end)
            val days = block.asSequence()
                .flatMap { line -> daysPatterns.asSequence().mapNotNull { it.find(line)?.groupValues?.get(1) } }
                .mapNotNull { it.replace(',', '.').toDoubleOrNull()?.toInt() }
                .filter { it in 0..31 }
                .firstOrNull()
            val candidates = block.mapIndexedNotNull { index, line ->
                val nums = moneyToken.findAll(line).mapNotNull { parseMoney(it.value) }.toList()
                nums.takeIf { it.size >= 8 }?.let { Candidate(start + index, it) }
            }
            val total = candidates.maxWithOrNull(compareBy<Candidate> { it.numbers.size }.thenBy { it.lineIndex })
            if (total != null) {
                val n = total.numbers
                val insurance = when {
                    n.size >= 4 -> n[3]
                    n.size >= 3 -> n[1] + n[2]
                    else -> null
                }
                return Metrics(insurance, days)
            }
            return Metrics(null, days)
        }
    }
}
