package gr.prosfora.app.ui.debts

import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.debt.AadeInstallmentParser
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** Compatibility overload for the actual parser Info type returned by DebtImporter. */
fun materializeInstallmentDebt(
    debt: DebtEntity,
    plan: AadeInstallmentParser.Info,
    mode: Any,
): List<DebtEntity> {
    if (mode.toString() != "INSTALLMENTS") {
        return listOf(debt.copy(amount = plan.totalAmount, dueDay = plan.firstDueDay))
    }
    fun lastBusinessDay(year: Int, month: Int): LocalDate {
        var date = YearMonth.of(year, month).atEndOfMonth()
        while (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            date = date.minusDays(1)
        }
        return date
    }
    fun roundMoney(value: Double): Double = Math.round(value * 100.0) / 100.0
    val firstDue = LocalDate.ofEpochDay(plan.firstDueDay)
    return (0 until plan.installmentCount).map { index ->
        val targetMonth = firstDue.plusMonths(index.toLong())
        val due = if (index == 0) firstDue else lastBusinessDay(targetMonth.year, targetMonth.monthValue)
        val amount = if (index == plan.installmentCount - 1) {
            roundMoney(plan.totalAmount - plan.installmentAmount * (plan.installmentCount - 1))
        } else plan.installmentAmount
        debt.copy(
            id = DebtEntity.idFor(debt.kind, debt.periodYear, debt.periodMonth, "${debt.reference}|dose:${index + 1}/${plan.installmentCount}", debt.personName),
            amount = amount,
            dueDay = due.toEpochDay(),
            description = buildString {
                append(debt.description.ifBlank { "Βεβαιωμένη οφειλή" })
                append(" · δόση ${index + 1}/${plan.installmentCount}")
            },
        )
    }
}
