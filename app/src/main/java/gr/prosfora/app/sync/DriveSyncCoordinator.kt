package gr.prosfora.app.sync

import android.content.Context
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.debt.DebtImporter
import gr.prosfora.app.debt.DebtRepository
import gr.prosfora.app.debug.DebugLog
import gr.prosfora.app.google.DriveClient
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.SheetsClient
import gr.prosfora.app.notify.DriveNotifier

/**
 * Ενιαίος συγχρονισμός για όλη την εφαρμογή.
 *
 * Δεν εξαρτάται από το ποια οθόνη είναι ανοιχτή:
 *  - συγχρονίζει τη βάση των Προσφορών
 *  - ελέγχει/εισάγει νέες Οφειλές
 *  - ειδοποιεί όταν εμφανιστεί νέα οφειλή
 */
object DriveSyncCoordinator {

    data class Result(
        val sheetSummary: String? = null,
        val importedDebts: List<DebtEntity> = emptyList(),
        val unreadableDebts: Int = 0,
    )

    suspend fun sync(
        context: Context,
        accessToken: String,
        syncSheet: Boolean = true,
    ): Result {
        val app = context.applicationContext
        val settings = GoogleSettings(app)
        val drive = DriveClient(accessToken)
        val repository = DebtRepository(app)

        DebugLog.log("sync") {
            "έναρξη ενιαίου συγχρονισμού · sheet=$syncSheet · " +
                "owner=${settings.ownerEmail.ifBlank { "άγνωστος" }}"
        }

        var sheetSummary: String? = null
        if (syncSheet && settings.spreadsheetId?.isNotBlank() == true) {
            sheetSummary = runCatching {
                val sheets = SheetsClient(accessToken)
                SheetSync(app, sheets, settings).sync().summary
            }.onFailure {
                DebugLog.log("sync", "Sheet sync απέτυχε: ${it.stackTraceToString()}")
            }.getOrNull()
            DebugLog.log("sync", "Sheet sync αποτέλεσμα: ${sheetSummary ?: "αποτυχία/χωρίς αποτέλεσμα"}")
        } else {
            DebugLog.log("sync", "Sheet sync παραλείφθηκε: δεν υπάρχει spreadsheetId ή ζητήθηκε μόνο Drive")
        }

        val report = runCatching {
            DebtImporter(drive, settings).scan(repository.importedFileIds()) { message ->
                DebugLog.log("sync", message)
            }
        }.onFailure {
            DebugLog.log("sync", "Debt scan απέτυχε: ${it.stackTraceToString()}")
        }.getOrNull()

        if (report == null) {
            return Result(sheetSummary = sheetSummary)
        }

        val validDebts = report.debts
        if (validDebts.isNotEmpty()) {
            DebugLog.log("sync", "νέες έγκυρες οφειλές προς αποθήκευση: ${validDebts.size}")
            repository.saveAll(validDebts)
            if (settings.notifyDriveChanges) {
                DriveNotifier.notifyDebts(app, validDebts)
            }
        }

        val unreadable = report.unreadable.size
        DebugLog.log(
            "sync",
            "ολοκλήρωση · scanned=${report.scanned} skipped=${report.skipped} " +
                "debts=${validDebts.size} unreadable=$unreadable",
        )

        return Result(
            sheetSummary = sheetSummary,
            importedDebts = validDebts,
            unreadableDebts = unreadable,
        )
    }
}
