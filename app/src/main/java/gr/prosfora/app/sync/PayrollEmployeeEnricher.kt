package gr.prosfora.app.sync

import android.util.Log
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity

/**
 * Enriches already-parsed payroll debts with the AM IKA printed on the same
 * payroll line. Uses the OCR text already produced by the importer; it never
 * downloads or OCRs the file a second time.
 *
 * Supported real-world line examples include leading OCR separators such as
 * "│" and multiple-name fields:
 *   1 005 BUTT HURARA ABDUL QAYYUM 305389566 305389566 ...
 *   2 011 GHONIM KHALED MOHAMED MOHAMED 201034132 ...
 */
object PayrollEmployeeEnricher {
    private val employeeLine = Regex(
        """(?:^|\\s)[^0-9A-ZΑ-Ω]*\\s*(\\d{1,3})\\s+([A-ZΑ-Ω0-9]{2,6})\\s+([A-ZΑ-Ω][A-ZΑ-Ω'\\-]*(?:\\s+[A-ZΑ-Ω][A-ZΑ-Ω'\\-]*){1,10})\\s+(\\d{9,10})(?=\\s|$)""",
        RegexOption.IGNORE_CASE,
    )

    fun enrich(debts: List<DebtEntity>, ocrText: String): List<DebtEntity> {
        if (debts.isEmpty() || ocrText.isBlank()) return debts

        val ikaByCode = linkedMapOf<String, String>()
        ocrText.lineSequence().forEach { rawLine ->
            val line = rawLine.removePrefix("\\uFEFF").trim()
            val match = employeeLine.find(line) ?: return@forEach
            val code = match.groupValues[2].trim()
            val ika = EmployeeEntity.normalizeIka(match.groupValues[4])
            if (ika.length in 9..10) {
                ikaByCode[code] = ika
                ikaByCode[code.trimStart('0')] = ika
            }
        }

        DebugLog.log("employees") {
            if (ikaByCode.isEmpty()) "payroll AM IKA mapping EMPTY · no payroll employee line matched"
            else "payroll AM IKA mapping: " + ikaByCode.entries.joinToString { "${it.key}->${it.value}" }
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
