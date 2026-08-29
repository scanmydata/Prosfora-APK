package gr.prosfora.app.sync

import gr.prosfora.app.data.db.DebtEntity

/** Holds the OCR payload of the current direct/manual payroll import until saveAll(). */
object PayrollImportSession {
    private var staged: Pair<String, List<DebtEntity>>? = null

    @Synchronized
    fun stage(ocrText: String, debts: List<DebtEntity>) {
        staged = ocrText to debts
    }

    @Synchronized
    fun consume(): Pair<String, List<DebtEntity>>? {
        val value = staged
        staged = null
        return value
    }
}
