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

/** Ενιαίος συγχρονισμός Sheet + Οφειλών + PDF archive. */
object DriveSyncCoordinator {
    data class Result(
        val sheetSummary: String? = null,
        val importedDebts: List<DebtEntity> = emptyList(),
        val unreadableDebts: Int = 0,
    )

    suspend fun sync(context: Context, accessToken: String, syncSheet: Boolean = true): Result {
        val app = context.applicationContext
        val settings = GoogleSettings(app)
        val drive = DriveClient(accessToken)
        val repository = DebtRepository(app)

        DebugLog.log("sync") {
            "έναρξη ενιαίου συγχρονισμού · sheet=$syncSheet · owner=${settings.ownerEmail.ifBlank { "άγνωστος" }}"
        }

        val sheetSummary = if (syncSheet && settings.spreadsheetId?.isNotBlank() == true) {
            runCatching {
                SheetSync(app, SheetsClient(accessToken), settings).sync().summary
            }.onFailure {
                DebugLog.log("sync", "Sheet sync απέτυχε: ${it.stackTraceToString()}")
            }.getOrNull()
        } else null

        DebugLog.log("sync", "Sheet αποτέλεσμα: ${sheetSummary ?: "παραλείφθηκε/χωρίς αποτέλεσμα"}")

        val report = runCatching {
            DebtImporter(drive, settings).scan(
                alreadyImported = repository.importedFileIds(),
                onProgress = { message -> DebugLog.log("sync", message) },
                includePdfArchive = true,
            )
        }.onFailure {
            DebugLog.log("sync", "Debt scan απέτυχε: ${it.stackTraceToString()}")
        }.getOrNull() ?: return Result(sheetSummary = sheetSummary)

        val validDebts = report.debts
        if (validDebts.isNotEmpty()) {
            repository.saveAll(validDebts)
            DebugLog.log("sync", "αποθηκεύτηκαν ${validDebts.size} νέες οφειλές")
            if (settings.notifyDriveChanges) {
                DriveNotifier.notifyDebts(app, validDebts)
            }
        }

        DebugLog.log(
            "sync",
            "τέλος · scanned=${report.scanned}, skipped=${report.skipped}, debts=${validDebts.size}",
        )
        return Result(sheetSummary, validDebts, report.unreadable.size)
    }
}
