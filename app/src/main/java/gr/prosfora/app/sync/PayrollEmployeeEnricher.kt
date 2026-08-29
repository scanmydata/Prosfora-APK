package gr.prosfora.app.sync

import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity

/**
 * Enriches already-parsed payroll debts with the AM IKA printed on the same
 * payroll line. The payroll parser already gives us personCode/personName;
 * this pass uses the OCR text that has already been produced, so it never
 * downloads or OCRs the file a second time.
 *
 * Expected line shape:
 *   1 005 BUTT HURARA ABDUL QAYYUM 305389566 ...
 */
object PayrollEmployeeEnricher {
    private val employeeLine = Regex(
        """^\s*\d{1,3}\s+([A-ZΑ-Ω0-9]{2,6})\s+.+?\s+(\d{9,10})(?=\s|$)""",
        RegexOption.IGNORE_CASE,
    )

    fun enrich(debts: List<DebtEntity>, ocrText: String): List<DebtEntity> {
        if (debts.isEmpty() || ocrText.isBlank()) return debts

        val ikaByCode = linkedMapOf<String, String>()
        ocrText.lineSequence().forEach { line ->
            val match = employeeLine.find(line) ?: return@forEach
            val code = match.groupValues[1].trim()
            val ika = EmployeeEntity.normalizeIka(match.groupValues[2])
            if (ika.length >= 9) {
                ikaByCode[code] = ika
                ikaByCode[code.trimStart('0')] = ika
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
