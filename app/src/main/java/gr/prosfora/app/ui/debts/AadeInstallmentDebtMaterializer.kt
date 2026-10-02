package gr.prosfora.app.ui.debts

import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.debt.AadeInstallmentParser
import gr.prosfora.app.debt.InstallmentPlan

/**
 * Οι γραμμές που θα αποθηκευτούν για μια οφειλή με πλάνο δόσεων.
 *
 * Μία υλοποίηση, στο [InstallmentPlan]. Υπήρχαν τρεις αντιγραφές του ίδιου
 * υπολογισμού —εδώ, στον διάλογο εισαγωγής και στον διάλογο ειδοποιήσεων— και
 * καμία δεν κρατούσε το πλάνο πάνω στην οφειλή: μόλις έκλεινε το παράθυρο, η
 * πληροφορία ότι η οφειλή σπάει σε δόσεις χανόταν.
 *
 * Το `mode` μένει `Any` για τους παλιούς καλούντες: οποιοδήποτε
 * «INSTALLMENTS» σημαίνει δόσεις, όλα τα άλλα μία συνολική οφειλή.
 */
fun materializeInstallmentDebt(
    debt: DebtEntity,
    plan: AadeInstallmentParser.Info,
    mode: Any,
): List<DebtEntity> {
    val parsed = InstallmentPlan.of(plan)
    val asInstallments = mode.toString() == "INSTALLMENTS"
    return InstallmentPlan.reshape(listOf(debt), parsed, asInstallments)
        .map { it.copy(installmentPlan = parsed.format()) }
}
