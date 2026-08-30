package gr.prosfora.app.sync

import android.content.Context
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.SheetsClient
import gr.prosfora.app.debt.DebtImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Compatibility entry point for payroll cost indexing.
 *
 * The employee-cost sheet must have one authoritative source: the persistent
 * monthly payroll snapshots. The previous implementation parsed the OCR a
 * second time and could overwrite correct snapshot values with a partial row.
 */
object PayrollCostIndexer {
    suspend fun index(context: Context, accessToken: String, found: DebtImporter.Found) = withContext(Dispatchers.IO) {
        if (!found.debts.any { it.kind.perPerson }) return@withContext

        val settings = GoogleSettings(context.applicationContext)
        if (settings.spreadsheetId.isNullOrBlank()) return@withContext

        // SheetSync reads PayrollEmployeeSnapshotStore for every local employee,
        // so alias assignment is never used as an inclusion criterion.
        SheetSync(context.applicationContext, SheetsClient(accessToken), settings).sync()
    }
}
