package gr.prosfora.app.debt

import gr.prosfora.app.data.db.DebtAgency
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.debug.DebugLog
import gr.prosfora.app.google.DriveClient
import gr.prosfora.app.google.DriveWorkspace
import gr.prosfora.app.google.GoogleSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Importer οφειλών: κοινή διαδρομή για εσωτερικά uploads και εξωτερικά Drive files. */
class DebtImporter(
    private val drive: DriveClient,
    private val settings: GoogleSettings,
) {

    private val workspace = DriveWorkspace(drive, settings)

    private val reader = DocumentText(
        drive = drive,
        ocr = settings.ocrApiKey
            .takeIf { it.isNotBlank() }
            ?.let { OcrSpaceClient(it) },
    )

    private val llm = LlmExtractor(
        settings.groqApiKey,
        settings.openRouterApiKey,
    ).takeIf { it.available }

    data class Found(
        val fileName: String,
        val driveFileId: String,
        val debts: List<DebtEntity>,
        val route: DocumentText.Route = DocumentText.Route.NONE,
        val note: String = "",
        val installmentPlan: AadeInstallmentParser.Info? = null,
        val afmMismatch: Boolean = false,
    ) {
        val recognised: Boolean
            get() = debts.isNotEmpty()
    }

    data class Report(
        val scanned: Int,
        val skipped: Int,
        val found: List<Found>,
    ) {
        val debts: List<DebtEntity>
            get() = found.flatMap { it.debts }

        val unreadable: List<Found>
            get() = found.filterNot { it.recognised }

        val routes: String
            get() = found
                .filter { it.recognised }
                .map { it.route.label }
                .distinct()
                .joinToString(", ")

        val summary: String
            get() = when {
                scanned == 0 && skipped > 0 -> "Όλα τα αρχεία έχουν ήδη διαβαστεί"
                scanned == 0 -> "Δεν βρέθηκαν νέα αρχεία."
                debts.isEmpty() -> "Διαβάστηκαν $scanned αρχεία, καμία οφειλή δεν αναγνωρίστηκε"
                else -> "Διαβάστηκαν $scanned αρχεία · ${debts.size} οφειλές · $routes"
            }
    }

    suspend fun importFile(
        fileName: String,
        bytes: ByteArray,
    ): Found = withContext(Dispatchers.IO) {
        DebugLog.log("debt-import", "Εσωτερική εισαγωγή: αρχείο=$fileName, bytes=${bytes.size}")
        val kind = DocumentBytes.kindOf(bytes)
            ?: error("Δέχεται PDF ή εικόνα (PNG / JPG)")

        val inbox = workspace.debtsFolder()
        DebugLog.log("debt-import", "Upload στο root Οφειλές: folder=$inbox")
        val id = drive.upload(
            name = fileName,
            bytes = bytes,
            mimeType = kind.mime,
            parentId = inbox,
        )
        settings.rememberDriveFiles(listOf(id))
        DebugLog.log("debt-import", "Upload ολοκληρώθηκε: fileId=$id")

        val found = read(fileName, id, bytes)
        DebugLog.log(
            "debt-import",
            "Αποτέλεσμα εσωτερικής εισαγωγής: afmMismatch=${found.afmMismatch}, debts=${found.debts.size}, installmentPlan=${found.installmentPlan != null}",
        )

        found.debts.firstOrNull()?.agency?.let { agency ->
            runCatching {
                DebugLog.log("debt-drive", "Μετακίνηση εσωτερικού αρχείου $id -> ${agency.label}")
                drive.moveToFolder(id, workspace.debtsFolder(agency))
                DebugLog.log("debt-drive", "Επιτυχής μετακίνηση $id -> ${agency.label}; το root Οφειλές καθαρίστηκε")
            }.onFailure {
                DebugLog.log("debt-drive", "ΑΠΟΤΥΧΙΑ μετακίνησης $id: ${it.message}")
            }
        }
        found
    }

    suspend fun scan(
        alreadyImported: Set<String>,
        onProgress: (String) -> Unit = {},
    ): Report = withContext(Dispatchers.IO) {
        DebugLog.log("debt-scan", "Έναρξη scan. alreadyImported=${alreadyImported.size}")
        var scanned = 0
        var skipped = 0
        val found = mutableListOf<Found>()

        val rootFolder = runCatching { workspace.debtsFolder() }.getOrNull()
        val folders = buildList {
            rootFolder?.let { add(it) }
            DebtAgency.entries.forEach { agency ->
                runCatching { workspace.debtsFolder(agency) }.getOrNull()?.let { add(it) }
            }
        }.distinct()

        DebugLog.log("debt-scan", "Folders προς scan=${folders.size}, root=$rootFolder")
        if (folders.isEmpty()) {
            onProgress("Δεν ήταν δυνατή η πρόσβαση στον φάκελο «Οφειλές».")
            return@withContext Report(0, 0, emptyList())
        }

        folders.forEach { folder ->
            DebugLog.log("debt-scan", "Σκανάρισμα folder=$folder")
            val files = try {
                workspace.documentsIn(folder)
            } catch (e: Exception) {
                DebugLog.log("debt-scan", "ΑΠΟΤΥΧΙΑ list folder=$folder: ${e.stackTraceToString()}")
                onProgress("Σφάλμα ανάγνωσης Drive: ${e.message ?: "άγνωστο σφάλμα"}")
                emptyList()
            }
            DebugLog.log("debt-scan", "Folder=$folder βρέθηκαν ${files.size} αρχεία")

            files.forEach { file ->
                if (file.id in alreadyImported) {
                    skipped++
                    DebugLog.log("debt-scan", "SKIP ήδη γνωστό fileId=${file.id}, name=${file.name}")
                    return@forEach
                }

                onProgress(file.name)
                scanned++
                DebugLog.log("debt-scan", "Ανάγνωση fileId=${file.id}, name=${file.name}, modifiedBy=${file.modifiedByEmail}")

                val bytes = try {
                    drive.download(file.id).also {
                        DebugLog.log("debt-scan", "Download ok fileId=${file.id}, bytes=${it.size}")
                    }
                } catch (e: Exception) {
                    DebugLog.log("debt-scan", "ΑΠΟΤΥΧΙΑ download fileId=${file.id}: ${e.stackTraceToString()}")
                    onProgress("Αποτυχία λήψης «${file.name}»: ${e.message ?: "άγνωστο σφάλμα"}")
                    null
                }

                try {
                    read(file.name, file.id, bytes).also { result ->
                        found += result
                        DebugLog.log(
                            "debt-scan",
                            "Parsed fileId=${file.id}: afmMismatch=${result.afmMismatch}, debts=${result.debts.size}, route=${result.route}, installmentPlan=${result.installmentPlan != null}, note=${result.note}",
                        )
                        result.debts.firstOrNull()?.agency?.let { agency ->
                            runCatching {
                                DebugLog.log("debt-drive", "Εξωτερικό αρχείο $file.id -> ${agency.label}")
                                drive.moveToFolder(file.id, workspace.debtsFolder(agency))
                                DebugLog.log("debt-drive", "Επιτυχής μετακίνηση $file.id -> ${agency.label}; δεν παραμένει στο root Οφειλές")
                            }.onFailure { moveError ->
                                DebugLog.log("debt-drive", "ΑΠΟΤΥΧΙΑ move fileId=${file.id}: ${moveError.stackTraceToString()}")
                                onProgress("Δεν μετακινήθηκε «${file.name}»: ${moveError.message ?: "άγνωστο σφάλμα"}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    DebugLog.log("debt-scan", "ΑΠΟΤΥΧΙΑ parse fileId=${file.id}: ${e.stackTraceToString()}")
                    onProgress("Αποτυχία ανάγνωσης «${file.name}»: ${e.message ?: "άγνωστο σφάλμα"}")
                }
            }
        }

        DebugLog.log("debt-scan", "Τέλος scan: scanned=$scanned, skipped=$skipped, found=${found.size}, debts=${found.sumOf { it.debts.size }}")
        Report(scanned = scanned, skipped = skipped, found = found)
    }

    private suspend fun read(
        fileName: String,
        driveFileId: String,
        bytes: ByteArray?,
    ): Found {
        DebugLog.log("debt-read", "read start: file=$fileName, driveFileId=$driveFileId, localBytes=${bytes?.size ?: 0}")
        val result = reader.read(
            bytes,
            driveFileId,
            fileName,
        ) { text ->
            val parsed = DebtParser.parse(text, fileName, driveFileId)
            DebugLog.log("debt-read", "DocumentText validator: debtParserCount=${parsed.size}, file=$fileName")
            parsed.isNotEmpty()
        }
        DebugLog.log("debt-read", "DocumentText route=${result.route}, chars=${result.text.length}, note=${result.note}")
        DebugLog.dump("debt-ocr", "OCR text για $fileName", result.text)

        val afmStatus = AadeInstallmentParser.afmStatus(result.text)
        DebugLog.log("debt-afm", "AFM status file=$fileName => $afmStatus, expected=802576637")
        if (afmStatus != AadeInstallmentParser.AfmStatus.MATCH) {
            val note = when (afmStatus) {
                AadeInstallmentParser.AfmStatus.MISMATCH -> "Απορρίφθηκε: το αρχείο δεν αφορά το ΑΦΜ 802576637."
                AadeInstallmentParser.AfmStatus.UNKNOWN -> "Δεν κατέστη δυνατή η επιβεβαίωση ότι το αρχείο αφορά το ΑΦΜ 802576637."
                AadeInstallmentParser.AfmStatus.MATCH -> ""
            }
            DebugLog.log("debt-afm", "Μπλοκάρισμα εισαγωγής file=$fileName: $note")
            return Found(
                fileName = fileName,
                driveFileId = driveFileId,
                debts = emptyList(),
                route = result.route,
                note = note,
                afmMismatch = true,
            )
        }

        val byRules = DebtParser.parse(result.text, fileName, driveFileId)
        val installmentPlan = AadeInstallmentParser.parse(result.text)
        DebugLog.log("debt-installments", "file=$fileName plan=$installmentPlan rules=${byRules.size}")
        val rulesForImport = if (installmentPlan != null) {
            byRules.map {
                it.copy(amount = installmentPlan.totalAmount, dueDay = installmentPlan.firstDueDay)
            }
        } else byRules

        val model = llm
        if (byRules.isNotEmpty() || result.text.isBlank() || model == null) {
            return Found(
                fileName = fileName,
                driveFileId = driveFileId,
                debts = rulesForImport,
                route = result.route,
                note = result.note,
                installmentPlan = installmentPlan,
            )
        }

        val extractedByModel = runCatching {
            model.extract(result.text, fileName, driveFileId)
        }.onFailure { DebugLog.log("debt-llm", "ΑΠΟΤΥΧΙΑ LLM file=$fileName: ${it.stackTraceToString()}") }
            .getOrDefault(emptyList())
        val byModel = if (installmentPlan != null) {
            extractedByModel.map {
                it.copy(amount = installmentPlan.totalAmount, dueDay = installmentPlan.firstDueDay)
            }
        } else extractedByModel

        return Found(
            fileName = fileName,
            driveFileId = driveFileId,
            debts = byModel,
            route = if (byModel.isEmpty()) result.route else DocumentText.Route.LLM,
            note = if (byModel.isEmpty()) result.note else "διαβάστηκε από μοντέλο",
            installmentPlan = installmentPlan,
        )
    }

    suspend fun folderUrl(): String = workspace.folderUrl(workspace.debtsFolder())
}
