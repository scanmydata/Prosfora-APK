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
        ocr = settings.ocrApiKey.takeIf { it.isNotBlank() }?.let { OcrSpaceClient(it) },
    )
    private val llm = LlmExtractor(settings.groqApiKey, settings.openRouterApiKey).takeIf { it.available }

    data class Found(
        val fileName: String,
        val driveFileId: String,
        val debts: List<DebtEntity>,
        val route: DocumentText.Route = DocumentText.Route.NONE,
        val note: String = "",
        val installmentPlan: AadeInstallmentParser.Info? = null,
        val afmMismatch: Boolean = false,
    ) { val recognised: Boolean get() = debts.isNotEmpty() }

    data class Report(val scanned: Int, val skipped: Int, val found: List<Found>) {
        val debts: List<DebtEntity> get() = found.flatMap { it.debts }
        val unreadable: List<Found> get() = found.filterNot { it.recognised }
        val routes: String get() = found.filter { it.recognised }.map { it.route.label }.distinct().joinToString(", ")
        val summary: String get() = when {
            scanned == 0 && skipped > 0 -> "Όλα τα αρχεία έχουν ήδη διαβαστεί"
            scanned == 0 -> "Δεν βρέθηκαν νέα αρχεία."
            debts.isEmpty() -> "Διαβάστηκαν $scanned αρχεία, καμία οφειλή δεν αναγνωρίστηκε"
            else -> "Διαβάστηκαν $scanned αρχεία · ${debts.size} οφειλές · $routes"
        }
    }

    suspend fun importFile(fileName: String, bytes: ByteArray): Found = withContext(Dispatchers.IO) {
        DebugLog.log("debt-import", "Εσωτερική εισαγωγή: $fileName, bytes=${bytes.size}")
        val kind = DocumentBytes.kindOf(bytes) ?: error("Δέχεται PDF ή εικόνα (PNG / JPG)")
        val inbox = workspace.debtsFolder()
        val id = drive.upload(fileName, bytes, kind.mime, inbox)
        settings.rememberDriveFiles(listOf(id))
        val found = read(fileName, id, bytes)
        DebugLog.log("debt-import", "Αποτέλεσμα: afmMismatch=${found.afmMismatch}, debts=${found.debts.size}, plan=${found.installmentPlan != null}")
        moveRecognised(id, fileName, found)
        found
    }

    suspend fun scan(
        alreadyImported: Set<String>,
        onProgress: (String) -> Unit = {},
        includePdfArchive: Boolean = false,
    ): Report = withContext(Dispatchers.IO) {
        DebugLog.log("debt-scan", "Έναρξη scan · alreadyImported=${alreadyImported.size} · includePdfArchive=$includePdfArchive")
        var scanned = 0
        var skipped = 0
        val found = mutableListOf<Found>()

        val rootFolder = runCatching { workspace.debtsFolder() }.getOrNull()
        val folders = buildList {
            rootFolder?.let(::add)
            DebtAgency.entries.forEach { agency -> runCatching { workspace.debtsFolder(agency) }.getOrNull()?.let(::add) }
            if (includePdfArchive) {
                runCatching { workspace.pdfFolder() }.getOrNull()?.let(::add)
                runCatching { workspace.pdfYears() }.getOrNull().orEmpty().forEach { add(it.id) }
            }
        }.distinct()

        DebugLog.log("debt-scan", "Folders=$folders")
        if (folders.isEmpty()) return@withContext Report(0, 0, emptyList())

        folders.forEach { folder ->
            val files = runCatching { workspace.documentsIn(folder) }
                .onFailure { DebugLog.log("debt-scan", "list failed folder=$folder: ${it.stackTraceToString()}") }
                .getOrDefault(emptyList())
            DebugLog.log("debt-scan", "folder=$folder files=${files.size}")

            files.forEach { file ->
                if (file.id in alreadyImported) {
                    skipped++
                    return@forEach
                }
                onProgress(file.name)
                scanned++
                val bytes = runCatching { drive.download(file.id) }
                    .onFailure { DebugLog.log("debt-scan", "download failed ${file.id}: ${it.stackTraceToString()}") }
                    .getOrNull()
                try {
                    read(file.name, file.id, bytes).also { result ->
                        found += result
                        DebugLog.log("debt-scan", "parsed ${file.id}: afmMismatch=${result.afmMismatch}, debts=${result.debts.size}")
                        moveRecognised(file.id, file.name, result)
                    }
                } catch (e: Exception) {
                    DebugLog.log("debt-scan", "parse failed ${file.id}: ${e.stackTraceToString()}")
                    onProgress("Αποτυχία ανάγνωσης «${file.name}»: ${e.message ?: "άγνωστο σφάλμα"}")
                }
            }
        }
        DebugLog.log("debt-scan", "Τέλος scan · scanned=$scanned skipped=$skipped debts=${found.sumOf { it.debts.size }}")
        Report(scanned, skipped, found)
    }

    private suspend fun moveRecognised(fileId: String, fileName: String, found: Found) {
        found.debts.firstOrNull()?.agency?.let { agency ->
            runCatching {
                drive.moveToFolder(fileId, workspace.debtsFolder(agency))
                DebugLog.log("debt-drive", "Ματακινήθηκε $fileName ($fileId) -> ${agency.label}; αφαιρέθηκε από την αρχική θέση")
            }.onFailure {
                DebugLog.log("debt-drive", "ΑΠΟΤΥΧΙΑ move $fileName ($fileId): ${it.stackTraceToString()}")
            }
        }
    }

    private suspend fun read(fileName: String, driveFileId: String, bytes: ByteArray?): Found {
        DebugLog.log("debt-read", "read start file=$fileName id=$driveFileId bytes=${bytes?.size ?: 0}")
        val result = reader.read(bytes, driveFileId, fileName) { text ->
            val parsed = DebtParser.parse(text, fileName, driveFileId)
            DebugLog.log("debt-read", "validator parsed=${parsed.size} file=$fileName")
            parsed.isNotEmpty()
        }
        DebugLog.log("debt-read", "route=${result.route} chars=${result.text.length} note=${result.note}")
        DebugLog.dump("debt-ocr", "OCR text για $fileName", result.text)

        val afmStatus = AadeInstallmentParser.afmStatus(result.text)
        DebugLog.log("debt-afm", "file=$fileName status=$afmStatus expected=802576637")
        if (afmStatus != AadeInstallmentParser.AfmStatus.MATCH) {
            val note = when (afmStatus) {
                AadeInstallmentParser.AfmStatus.MISMATCH -> "Απορρίφθηκε: το αρχείο δεν αφορά το ΑΦΜ 802576637."
                AadeInstallmentParser.AfmStatus.UNKNOWN -> "Δεν κατέστη δυνατή η επιβεβαίωση ότι το αρχείο αφορά το ΑΦΜ 802576637."
                AadeInstallmentParser.AfmStatus.MATCH -> ""
            }
            return Found(fileName, driveFileId, emptyList(), result.route, note, afmMismatch = true)
        }

        val byRules = DebtParser.parse(result.text, fileName, driveFileId)
        val installmentPlan = AadeInstallmentParser.parse(result.text)
        val rulesForImport = if (installmentPlan != null) {
            byRules.map { it.copy(amount = installmentPlan.totalAmount, dueDay = installmentPlan.firstDueDay) }
        } else byRules
        val model = llm
        if (byRules.isNotEmpty() || result.text.isBlank() || model == null) {
            return Found(fileName, driveFileId, rulesForImport, result.route, result.note, installmentPlan)
        }
        val extractedByModel = runCatching { model.extract(result.text, fileName, driveFileId) }
            .onFailure { DebugLog.log("debt-llm", "failed $fileName: ${it.stackTraceToString()}") }
            .getOrDefault(emptyList())
        val byModel = if (installmentPlan != null) {
            extractedByModel.map { it.copy(amount = installmentPlan.totalAmount, dueDay = installmentPlan.firstDueDay) }
        } else extractedByModel
        return Found(fileName, driveFileId, byModel, if (byModel.isEmpty()) result.route else DocumentText.Route.LLM, if (byModel.isEmpty()) result.note else "διαβάστηκε από μοντέλο", installmentPlan)
    }

    suspend fun folderUrl(): String = workspace.folderUrl(workspace.debtsFolder())
}
