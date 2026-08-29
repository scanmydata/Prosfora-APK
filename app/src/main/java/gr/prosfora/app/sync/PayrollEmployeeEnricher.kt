package gr.prosfora.app.sync

import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity

/**
 * Enriches payroll debts with the AM IKA that is printed on the same employee
 * row. Uses only OCR text already produced by the importer.
 *
 * Real payroll row:
 *   1 005 BUTT HURARA ABDUL QAYYUM 305389566 305389566 22119904237 ...
 * The first 9-10 digit number after the employee code is the AM IKA.
 */
object PayrollEmployeeEnricher {
    private val employeeRow = Regex(
        """^.*?\\b\\d{1,3}\\s+([A-ZΑ-Ω0-9]{2,6})\\s+(.+)$""",
        RegexOption.IGNORE_CASE,
    )
    private val ikaNumber = Regex("""(?<!\\d)\\d{9,10}(?!\\d)""")

    fun enrich(debts: List<DebtEntity>, ocrText: String): List<DebtEntity> {
        if (debts.isEmpty() || ocrText.isBlank()) return debts

        val ikaByCode = linkedMapOf<String, String>()
        ocrText.lineSequence().forEach { rawLine ->
            val line = rawLine.removePrefix("\\uFEFF").trim()
            val row = employeeRow.find(line) ?: return@forEach
            val code = row.groupValues[1].trim()
            val rest = row.groupValues[2]
            val ika = ikaNumber.find(rest)?.value
                ?.let(EmployeeEntity::normalizeIka)
                ?: return@forEach

            if (ika.length in 9..10) {
                ikaByCode[code] = ika
                ikaByCode[code.trimStart('0')] = ika
            }
        }

        DebugLog.log("employees") {
            if (ikaByCode.isEmpty()) {
                "payroll AM IKA mapping EMPTY · no employee header row matched"
            } else {
                "payroll AM IKA mapping: " +
                    ikaByCode.entries
                        .filter { it.key.isNotBlank() }
                        .distinctBy { it.key }
                        .joinToString { "${it.key}->${it.value}" }
            }
        }

        if (ikaByCode.isEmpty()) return debts

        return debts.map { debt ->
            if (!debt.kind.perPerson || debt.amIka.isNotBlank()) return@map debt
            val code = debt.personCode.trim()
            val ika = ikaByCode[code]
                ?: ikaByCode[code.trimStart('0')]
                ?: return@map debt
            debt.copy(amIka = ika)
        }
    }
}
