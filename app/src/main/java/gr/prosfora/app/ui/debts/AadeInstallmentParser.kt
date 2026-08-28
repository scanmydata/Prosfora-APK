package gr.prosfora.app.ui.debts

import java.time.LocalDate

/** UI-facing adapter for the AADE installment parser. */
object AadeInstallmentParser {
    data class Info(
        val totalAmount: Double,
        val installmentAmount: Double,
        val installmentCount: Int,
        val firstDueDay: Long,
    )

    fun isAadeDocument(text: String): Boolean =
        gr.prosfora.app.debt.AadeInstallmentParser.isAadeDocument(text)

    fun afmMatches(text: String): Boolean =
        gr.prosfora.app.debt.AadeInstallmentParser.afmMatches(text)

    fun parse(text: String): Info? =
        gr.prosfora.app.debt.AadeInstallmentParser.parse(text)?.let {
            Info(
                totalAmount = it.totalAmount,
                installmentAmount = it.installmentAmount,
                installmentCount = it.installmentCount,
                firstDueDay = it.firstDueDay,
            )
        }
}
