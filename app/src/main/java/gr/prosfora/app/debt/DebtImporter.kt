package gr.prosfora.app.debt

import gr.prosfora.app.data.db.DebtAgency
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.debug.DebugLog
import gr.prosfora.app.google.DriveClient
import gr.prosfora.app.google.DriveWorkspace
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.sync.PayrollEmployeeSnapshotStore
import gr.prosfora.app.sync.PayrollInsuranceDaysStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DebtImporter(private val drive: DriveClient, private val settings: GoogleSettings) {
    private val workspace = DriveWorkspace(drive, settings)
    private val reader = DocumentText(drive = drive, ocr = settings.ocrApiKey.takeIf { it.isNotBlank() }?.let { OcrSpaceClient(it) })
    private val llm = LlmExtractor(settings.groqApiKey, settings.openRouterApiKey).takeIf { it.available }

    data class Found(
        val fileName: String,
        val driveFileId: String,
        val debts: List<DebtEntity>,
        val route: DocumentText.Route = DocumentText.Route.NONE,
        val note: String = "",
        val installmentPlan: AadeInstallmentParser.Info? = null,
        val afmMismatch: Boolean = false,
        val ocrText: String = "",
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
        if (!found.afmMismatch && found.debts.isNotEmpty()) {
            PayrollInsuranceDaysStore.record(this@DebtImporter.settingsContext(), found.ocrText, found.debts)
            PayrollEmployeeSnapshotStore.record(this@DebtImporter.settingsContext(), found.ocrText, found.debts)
        }
        DebugLog.log("debt-import", "Αποτέλεσμα: afmMismatch=${found.afmMismatch}, debts=${found.debts.size}, plan=${found.installmentPlan != null}")
        moveRecognised(id, fileName, found)
        found
    }

    suspend fun scan(alreadyImported: Set<String>, onProgress: (String) -> Unit): Report = scan(alreadyImported, onProgress, false)

    suspend fun scan(
        alreadyImported: Set<String>,
        onProgress: (String) -> Unit = {},
        includePdfArchive: Boolean = false,
        onFound: suspend (Found) -> Unit = {},
    ): Report = withContext(Dispatchers.IO) {
        DebugLog.log("debt-scan", "Έναρξη scan · alreadyImported=${alreadyImported.size} · includePdfArchive=$includePdfArchive")
        var scanned = 0
        var skipped = 0
        val found = mutableListOf<Found>()
        val processed = alreadyImported.toMutableSet()
        val rootFolder = runCatching { workspace.debtsFolder() }.getOrNull()
        val folders = buildList {
            rootFolder?.let(::add)
            DebtAgency.entries.forEach { agency -> runCatching { workspace.debtsFolder(agency) }.getOrNull()?.let(::add) }
            if (includePdfArchive) {
                runCatching { workspace.pdfFolder() }.getOrNull()?.let(::add)
                runCatching { workspace.pdfYears() }.getOrNull().orEmpty().forEach { add(it.id) }
            }
        }.distinct()
        if (folders.isEmpty()) return@withContext Report(0, 0, emptyList())

        folders.forEach { folder ->
            val files = runCatching { workspace.documentsIn(folder) }
                .onFailure { DebugLog.log("debt-scan", "list failed folder=$folder: ${it.stackTraceToString()}") }
                .getOrDefault(emptyList())
            files.forEach { file ->
                if (!processed.add(file.id)) {
                    skipped++
                    DebugLog.log("debt-scan", "SKIP ήδη επεξεργασμένο fileId=${file.id}, name=${file.name}")
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
                        DebugLog.log("debt-scan", "parsed ${file.id}: afmMismatch=${result.afmMismatch}, debts=${result.debts.size}, plan=${result.installmentPlan != null}")
                        if (!result.afmMismatch && (result.debts.isNotEmpty() || result.installmentPlan != null)) {
                            runCatching { onFound(result) }.onFailure { DebugLog.log("debt-scan", "onFound failed ${file.id}: ${it.stackTraceToString()}") }
                        }
                        moveRecognised(file.id, file.name, result)
                    }
                } catch (e: Exception) {
                    DebugLog.log("debt-scan", "parse failed ${file.id}: ${e.stackTraceToString()}")
                    onProgress("Αποτυχία ανάγνωσης «${file.name}»: ${e.message ?: "άγνωστο σφάλμα"}")
                }
            }
        }
        Report(scanned, skipped, found)
    }

    private suspend fun moveRecognised(fileId: String, fileName: String, found: Found) {
        val agency = found.debts.firstOrNull()?.agency ?: found.installmentPlan?.let { DebtAgency.AADE }
        agency?.let {
            runCatching {
                drive.moveToFolder(fileId, workspace.debtsFolder(it))
                DebugLog.log("debt-drive", "Μετακινήθηκε $fileName ($fileId) -> ${it.label}; αφαιρέθηκε από την αρχική θέση")
            }.onFailure { DebugLog.log("debt-drive", "ΑΠΟΤΥΧΙΑ move $fileName ($fileId): ${it.stackTraceToString()}") }
        } ?: DebugLog.log("debt-drive", "Δεν βρέθηκε προορισμός για $fileName ($fileId)")
    }

    private suspend fun read(fileName: String, driveFileId: String, bytes: ByteArray?): Found {
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
            return Found(fileName, driveFileId, emptyList(), result.route, note, afmMismatch = true, ocrText = result.text)
        }

        val byRules = enrichPayrollAmIka(result.text, DebtParser.parse(result.text, fileName, driveFileId))
        val installmentPlan = AadeInstallmentParser.parse(result.text)
        val rulesForImport = if (installmentPlan != null) {
            byRules.map { it.copy(amount = installmentPlan.totalAmount, dueDay = installmentPlan.firstDueDay) }
        } else byRules

        val model = llm
        if (byRules.isNotEmpty() || result.text.isBlank() || model == null) {
            return Found(fileName, driveFileId, rulesForImport, result.route, result.note, installmentPlan, ocrText = result.text)
        }

        val extractedByModel = runCatching { model.extract(result.text, fileName, driveFileId) }
            .onFailure { DebugLog.log("debt-llm", "failed $fileName: ${it.stackTraceToString()}") }
            .getOrDefault(emptyList())
        val byModel = enrichPayrollAmIka(
            result.text,
            if (installmentPlan != null) extractedByModel.map { it.copy(amount = installmentPlan.totalAmount, dueDay = installmentPlan.firstDueDay) } else extractedByModel,
        )
        return Found(
            fileName,
            driveFileId,
            byModel,
            if (byModel.isEmpty()) result.route else DocumentText.Route.LLM,
            if (byModel.isEmpty()) result.note else "διαβάστηκε από μοντέλο",
            installmentPlan,
            ocrText = result.text,
        )
    }

    private fun payrollAmIkaByCode(text: String): Map<String, String> {
        val header = Regex("""^\s*\d{1,3}\s+([0-9]{2,6})\s+(.+)$""")
        val longNumber = Regex("""(?<!\d)\d{7,12}(?!\d)""")
        val result = linkedMapOf<String, String>()
        text.lines().forEach { line ->
            val match = header.find(line) ?: return@forEach
            val code = match.groupValues[1]
            val rest = match.groupValues[2]
            val amIka = longNumber.find(rest)?.value?.let(EmployeeEntity::normalizeIka).orEmpty()
            if (amIka.isNotBlank()) result[code] = amIka
        }
        if (result.isNotEmpty()) DebugLog.log("employees", "payroll ΑΜ ΙΚΑ mapping · unique=${result.values.toSet().size} · codes=${result.size}")
        else DebugLog.log("employees", "payroll ΑΜ ΙΚΑ mapping EMPTY · no payroll header row matched")
        return result
    }

    private fun enrichPayrollAmIka(text: String, rows: List<DebtEntity>): List<DebtEntity> {
        if (rows.isEmpty()) return rows
        val mapping = payrollAmIkaByCode(text)
        if (mapping.isEmpty()) return rows
        return rows.map { row ->
            if (!row.kind.perPerson) row else row.copy(
                amIka = EmployeeEntity.normalizeIka(
                    mapping[row.personCode]
                        ?: mapping.entries.firstOrNull { it.key.trimStart('0') == row.personCode.trimStart('0') }?.value
                        ?: "",
                ),
            )
        }
    }

    private fun settingsContext(): android.content.Context {
        val field = settings.javaClass.declaredFields.firstOrNull { it.name == "context" }
        field?.isAccessible = true
        return (field?.get(settings) as? android.content.Context)?.applicationContext
            ?: error("GoogleSettings context unavailable")
    }

    suspend fun folderUrl(): String = workspace.folderUrl(workspace.debtsFolder())
}
