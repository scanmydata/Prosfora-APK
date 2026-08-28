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
import gr.prosfora.app.notify.PendingDebtNotificationStore

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

        val alreadyImported = repository.importedFileIds() + PendingDebtNotificationStore.fileIds(app)
        val report = runCatching {
            DebtImporter(drive, settings).scan(
                alreadyImported = alreadyImported,
                onProgress = { message -> DebugLog.log("sync", message) },
                includePdfArchive = true,
            )
        }.onFailure {
            DebugLog.log("sync", "Debt scan απέτυχε: ${it.stackTraceToString()}")
        }.getOrNull() ?: return Result(sheetSummary = sheetSummary)

        val pendingInstallments = report.found.filter {
            !it.afmMismatch && it.debts.isNotEmpty() && it.installmentPlan != null
        }
        val immediateDebts = report.found
            .filter { !it.afmMismatch && it.installmentPlan == null }
            .flatMap { it.debts }

        if (immediateDebts.isNotEmpty()) {
            repository.saveAll(immediateDebts)
            DebugLog.log("sync", "αποθηκεύτηκαν ${immediateDebts.size} νέες οφειλές χωρίς πλάνο δόσεων")
        }

        if (pendingInstallments.isNotEmpty()) {
            PendingDebtNotificationStore.enqueue(app, pendingInstallments)
            DebugLog.log("sync", "σε εκκρεμότητα για επιλογή δόσεων: ${pendingInstallments.size} αρχεία")
        }

        val notifyDebts = immediateDebts + pendingInstallments.flatMap { it.debts }
        if (settings.notifyDriveChanges && notifyDebts.isNotEmpty()) {
            DriveNotifier.notifyDebts(
                context = app,
                debts = notifyDebts,
                openPendingInstallments = pendingInstallments.isNotEmpty(),
            )
        }

        DebugLog.log(
            "sync",
            "τέλος · scanned=${report.scanned}, skipped=${report.skipped}, " +
                "saved=${immediateDebts.size}, pendingInstallments=${pendingInstallments.size}",
        )
        return Result(sheetSummary, immediateDebts, report.unreadable.size)
    }
}
