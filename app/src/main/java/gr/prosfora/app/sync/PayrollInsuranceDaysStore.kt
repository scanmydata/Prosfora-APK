package gr.prosfora.app.sync

import android.content.Context
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity
import org.json.JSONObject

/**
 * Persists insurance days captured during payroll import.
 * No Drive download/OCR is performed when an employee card is opened.
 */
object PayrollInsuranceDaysStore {
    private const val PREFS = "payroll_insurance_days"
    private const val KEY = "days_by_period"

    fun record(context: Context, ocrText: String, debts: List<DebtEntity>) {
        if (ocrText.isBlank() || debts.isEmpty()) return

        val byCode = extractByCode(ocrText)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val byIka = JSONObject(prefs.getString(KEY, "{}") ?: "{}")

        debts.filter { it.kind.perPerson }.forEach { debt ->
            val ika = EmployeeEntity.normalizeIka(debt.amIka)
            if (ika.isBlank() || debt.periodYear <= 0 || debt.periodMonth !in 1..12) return@forEach
            val days = byCode[debt.personCode.trimStart('0')]
                ?: byCode[debt.personCode]
                ?: return@forEach
            if (days <= 0) return@forEach

            val key = "$ika:${debt.periodYear}-${debt.periodMonth}"
            val current = byIka.optInt(key, 0)
            if (days > current) byIka.put(key, days)
        }

        prefs.edit().putString(KEY, byIka.toString()).apply()
    }

    fun daysFor(context: Context, amIka: String, year: Int, month: Int): Int {
        val ika = EmployeeEntity.normalizeIka(amIka)
        if (ika.isBlank() || year <= 0 || month !in 1..12) return 0
        val data = JSONObject(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "{}") ?: "{}",
        )
        return data.optInt("$ika:$year-$month", 0)
    }

    fun total(context: Context, amIka: String): Int {
        val ika = EmployeeEntity.normalizeIka(amIka)
        if (ika.isBlank()) return 0
        val data = JSONObject(
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY, "{}") ?: "{}",
        )
        var total = 0
        data.keys().forEach { key ->
            if (key.startsWith("$ika:")) total += data.optInt(key, 0)
        }
        return total
    }

    private fun extractByCode(text: String): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        val header = Regex("""^\s*\d{1,3}\s+([A-ZΑ-Ω0-9]{2,6})\s+(.+)$""")
        val explicitDays = Regex(
            """(?:ΗΜΕΡΕΣ|ΗΜ\.?)[\s._-]*(?:ΑΣΦΑΛΙΣΗΣ|ΑΣΦ\.?)[\s:=-]*(\d{1,2})(?!\d)""",
            RegexOption.IGNORE_CASE,
        )
        val longNumber = Regex("""(?<!\d)\d{7,12}(?!\d)""")

        text.lines().forEach { line ->
            val match = header.find(line) ?: return@forEach
            val code = match.groupValues[1].trim()
            val rest = match.groupValues[2]

            val explicit = explicitDays.find(rest)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (explicit != null && explicit in 1..31) {
                result[code] = explicit
                result[code.trimStart('0')] = explicit
                return@forEach
            }

            val ika = longNumber.find(rest) ?: return@forEach
            val afterIka = rest.substring(ika.range.last + 1)
            val candidate = Regex("""(?<!\d)(\d{1,2})(?!\d)""")
                .find(afterIka)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (candidate != null && candidate in 1..31) {
                result[code] = candidate
                result[code.trimStart('0')] = candidate
            }
        }
        return result
    }
}
