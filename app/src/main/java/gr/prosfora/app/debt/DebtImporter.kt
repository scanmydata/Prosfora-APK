package gr.prosfora.app.debt

import gr.prosfora.app.data.db.DebtAgency
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.google.DriveClient
import gr.prosfora.app.google.DriveWorkspace
import gr.prosfora.app.google.GoogleSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Φέρνει τις οφειλές από τα παραστατικά που ζουν στο Drive.
 *
 * Δύο δρόμοι, ίδιο τέλος:
 *
 *  * ο χρήστης διαλέγει PDF ή στιγμιότυπο οθόνης από τη συσκευή → ανεβαίνει
 *    αντίγραφο, διαβάζεται, και μπαίνει στον φάκελο του φορέα που βρέθηκε
 *  * ο χρήστης έχει ήδη ρίξει αρχεία στους φακέλους → η σάρωση τα βρίσκει μόνη της
 *
 * Το κείμενο βγαίνει με σειρά προτεραιότητας — εξαγωγή από το αρχείο, μετά
 * ocr.space, μετά OCR του Drive· βλ. [DocumentText].
 *
 * Τίποτα δεν αποθηκεύεται μόνο του: το [Found] πάει στην οθόνη, ο χρήστης
 * βλέπει τι διαβάστηκε και με ποιον τρόπο, και εγκρίνει.
 */
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

    /** Το δίχτυ κάτω από τους κανόνες. Χωρίς κλειδιά, δεν υπάρχει καν. */
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

        /** Πώς διαβάστηκε — ενδιαφέρει τον χρήστη πριν εμπιστευτεί τα ποσά. */
        val routes: String
            get() = found
                .filter { it.recognised }
                .map { it.route.label }
                .distinct()
                .joinToString(", ")

        val summary: String
            get() = when {
                scanned == 0 && skipped > 0 ->
                    "Όλα τα αρχεία έχουν ήδη διαβαστεί"

                scanned == 0 ->
                    "Δεν βρέθηκαν νέα αρχεία. Έλεγξε ότι τα αρχεία βρίσκονται " +
                        "στον φάκελο «Οφειλές» και ότι έχει εγκριθεί η πρόσβαση " +
                        "του Google Drive."

                debts.isEmpty() ->
                    "Διαβάστηκαν $scanned αρχεία, καμία οφειλή δεν αναγνωρίστηκε"

                else ->
                    "Διαβάστηκαν $scanned αρχεία · " +
                        "${debts.size} οφειλές · $routes"
            }
    }

    suspend fun importFile(
        fileName: String,
        bytes: ByteArray,
    ): Found = withContext(Dispatchers.IO) {
        val kind = DocumentBytes.kindOf(bytes)
            ?: error("Δέχεται PDF ή εικόνα (PNG / JPG)")

        val inbox = workspace.debtsFolder()
        val id = drive.upload(
            name = fileName,
            bytes = bytes,
            mimeType = kind.mime,
            parentId = inbox,
        )

        settings.rememberDriveFiles(listOf(id))

        val found = read(
            fileName = fileName,
            driveFileId = id,
            bytes = bytes,
        )

        found.debts.firstOrNull()?.agency?.let { agency ->
            runCatching {
                drive.moveToFolder(
                    id,
                    workspace.debtsFolder(agency),
                )
            }
        }

        found
    }

    suspend fun scan(
        alreadyImported: Set<String>,
        onProgress: (String) -> Unit = {},
    ): Report = withContext(Dispatchers.IO) {
        var scanned = 0
        var skipped = 0
        val found = mutableListOf<Found>()

        val folders = buildList {
            runCatching { workspace.debtsFolder() }.getOrNull()?.let { add(it) }
            DebtAgency.entries.forEach { agency ->
                runCatching { workspace.debtsFolder(agency) }.getOrNull()?.let { add(it) }
            }
        }

        if (folders.isEmpty()) {
            onProgress("Δεν ήταν δυνατή η πρόσβαση στον φάκελο «Οφειλές».")
            return@withContext Report(0, 0, emptyList())
        }

        folders.forEach { folder ->
            val files = try {
                workspace.documentsIn(folder)
            } catch (e: Exception) {
                onProgress(
                    "Σφάλμα ανάγνωσης Drive: ${e.message ?: "άγνωστο σφάλμα"}",
                )
                emptyList()
            }

            files.forEach { file ->
                if (file.id in alreadyImported) {
                    skipped++
                    return@forEach
                }

                onProgress(file.name)
                scanned++

                val bytes = try {
                    drive.download(file.id)
                } catch (e: Exception) {
                    onProgress(
                        "Αποτυχία λήψης «${file.name}»: ${e.message ?: "άγνωστο σφάλμα"}",
                    )
                    null
                }

                try {
                    read(
                        fileName = file.name,
                        driveFileId = file.id,
                        bytes = bytes,
                    ).also { result ->
                        found += result

                        // Αρχεία που μπήκαν απευθείας στο Drive μετακινούνται
                        // μόνο αν αναγνωρίστηκε έγκυρη οφειλή. Λάθος ΑΦΜ/άγνωστα
                        // αρχεία μένουν στη θέση τους για έλεγχο.
                        result.debts.firstOrNull()?.agency?.let { agency ->
                            runCatching {
                                drive.moveToFolder(
                                    file.id,
                                    workspace.debtsFolder(agency),
                                )
                            }.onFailure { moveError ->
                                onProgress(
                                    "Δεν μετακινήθηκε «${file.name}»: " +
                                        (moveError.message ?: "άγνωστο σφάλμα"),
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    onProgress(
                        "Αποτυχία ανάγνωσης «${file.name}»: ${e.message ?: "άγνωστο σφάλμα"}",
                    )
                }
            }
        }

        Report(scanned = scanned, skipped = skipped, found = found)
    }

    private suspend fun read(
        fileName: String,
        driveFileId: String,
        bytes: ByteArray?,
    ): Found {
        val result = reader.read(
            bytes,
            driveFileId,
            fileName,
        ) { text ->
            DebtParser.parse(text, fileName, driveFileId).isNotEmpty()
        }

        // Η εισαγωγή επιτρέπεται ΜΟΝΟ όταν το ΑΦΜ επιβεβαιωθεί.
        // Δεν αρκεί να είναι «γνωστό» έγγραφο ΑΑΔΕ: και τα εσωτερικά uploads
        // και τα εξωτερικά αρχεία Drive πρέπει να περάσουν από τον ίδιο έλεγχο.
        val afmStatus = AadeInstallmentParser.afmStatus(result.text)
        if (afmStatus != AadeInstallmentParser.AfmStatus.MATCH) {
            val note = when (afmStatus) {
                AadeInstallmentParser.AfmStatus.MISMATCH ->
                    "Απορρίφθηκε: το αρχείο δεν αφορά το ΑΦΜ 802576637."
                AadeInstallmentParser.AfmStatus.UNKNOWN ->
                    "Δεν κατέστη δυνατή η επιβεβαίωση ότι το αρχείο αφορά το ΑΦΜ 802576637."
                AadeInstallmentParser.AfmStatus.MATCH -> ""
            }
            return Found(
                fileName = fileName,
                driveFileId = driveFileId,
                debts = emptyList(),
                route = result.route,
                note = note,
                afmMismatch = true,
            )
        }

        val byRules = DebtParser.parse(
            result.text,
            fileName,
            driveFileId,
        )
        val installmentPlan = AadeInstallmentParser.parse(result.text)
        val rulesForImport = if (installmentPlan != null) {
            byRules.map {
                it.copy(amount = installmentPlan.totalAmount, dueDay = installmentPlan.firstDueDay)
            }
        } else {
            byRules
        }
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
        }.getOrDefault(emptyList())
        val byModel = if (installmentPlan != null) {
            extractedByModel.map {
                it.copy(amount = installmentPlan.totalAmount, dueDay = installmentPlan.firstDueDay)
            }
        } else {
            extractedByModel
        }

        return Found(
            fileName = fileName,
            driveFileId = driveFileId,
            debts = byModel,
            route = if (byModel.isEmpty()) result.route else DocumentText.Route.LLM,
            note = if (byModel.isEmpty()) result.note else "διαβάστηκε από μοντέλο",
            installmentPlan = installmentPlan,
        )
    }

    suspend fun folderUrl(): String =
        workspace.folderUrl(workspace.debtsFolder())
}
