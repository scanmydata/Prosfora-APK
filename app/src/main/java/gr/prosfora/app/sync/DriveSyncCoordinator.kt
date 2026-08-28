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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

object DriveSyncCoordinator {
    data class Result(val sheetSummary: String? = null, val importedDebts: List<DebtEntity> = emptyList(), val unreadableDebts: Int = 0)

    suspend fun sync(context: Context, accessToken: String, syncSheet: Boolean = true): Result = coroutineScope {
        val app = context.applicationContext
        val settings = GoogleSettings(app)
        val drive = DriveClient(accessToken)
        val repository = DebtRepository(app)
        DebugLog.log("sync") { "έναρξη ενιαίου συγχρονισμού · sheet=$syncSheet · owner=${settings.ownerEmail.ifBlank { "άγνωστος" }}" }
        val sheetJob = if (syncSheet && settings.spreadsheetId?.isNotBlank() == true) async {
            runCatching { SheetSync(app, SheetsClient(accessToken), settings).sync().summary }
                .onFailure { DebugLog.log("sync", "Sheet sync απέτυχε: ${it.stackTraceToString()}") }.getOrNull()
        } else null
        val alreadyImported = repository.importedFileIds() + PendingDebtNotificationStore.fileIds(app)
        val deferInstallments = settings.notifyDriveChanges
        val savedIds = mutableSetOf<String>()
        val pendingFiles = mutableSetOf<String>()
        val notifiedFiles = mutableSetOf<String>()
        val savedDebts = mutableListOf<DebtEntity>()
        val report = runCatching {
            DebtImporter(drive, settings).scan(
                alreadyImported = alreadyImported,
                onProgress = { message -> DebugLog.log("sync", message) },
                includePdfArchive = true,
                onFound = { found ->
                    if (found.afmMismatch || found.debts.isEmpty()) return@scan
                    try {
                        PayrollCostIndexer.index(app, accessToken, found)
                    } catch (e: Exception) {
                        DebugLog.log("payroll-cost", "Αποτυχία index κόστους ${found.fileName}: ${e.stackTraceToString()}")
                    }
                    val pending = deferInstallments && found.installmentPlan != null
                    if (pending) {
                        if (pendingFiles.add(found.driveFileId)) {
                            PendingDebtNotificationStore.enqueue(app, listOf(found))
                            DebugLog.log("sync", "άμεση εκκρεμής ειδοποίηση δόσεων · file=${found.fileName}")
                        }
                        if (notifiedFiles.add(found.driveFileId)) DriveNotifier.notifyDebts(app, found.debts, openPendingInstallments = true)
                    } else {
                        val fresh = found.debts.filter { savedIds.add(it.id) }
                        if (fresh.isNotEmpty()) {
                            repository.saveAll(fresh)
                            savedDebts += fresh
                            DebugLog.log("sync", "άμεση αποθήκευση ${fresh.size} οφειλών από ${found.fileName}")
                            if (notifiedFiles.add(found.driveFileId)) DriveNotifier.notifyDebts(app, fresh, openPendingInstallments = false)
                        }
                    }
                },
            )
        }.onFailure { DebugLog.log("sync", "Debt scan απέτυχε: ${it.stackTraceToString()}") }.getOrNull()
        val sheetSummary = sheetJob?.await()
        if (report == null) return@coroutineScope Result(sheetSummary = sheetSummary)
        report.found.forEach { found ->
            if (found.afmMismatch || found.debts.isEmpty()) return@forEach
            val pending = deferInstallments && found.installmentPlan != null
            if (pending) {
                if (pendingFiles.add(found.driveFileId)) PendingDebtNotificationStore.enqueue(app, listOf(found))
                if (notifiedFiles.add(found.driveFileId)) DriveNotifier.notifyDebts(app, found.debts, openPendingInstallments = true)
            } else {
                val fresh = found.debts.filter { savedIds.add(it.id) }
                if (fresh.isNotEmpty()) {
                    repository.saveAll(fresh)
                    savedDebts += fresh
                    if (notifiedFiles.add(found.driveFileId)) DriveNotifier.notifyDebts(app, fresh, openPendingInstallments = false)
                }
            }
        }
        DebugLog.log("sync", "τέλος · scanned=${report.scanned}, skipped=${report.skipped}, saved=${savedDebts.size}, pendingInstallments=${pendingFiles.size}, notifications=${notifiedFiles.size}")
        Result(sheetSummary, savedDebts.distinctBy { it.id }, report.unreadable.size)
    }
}
