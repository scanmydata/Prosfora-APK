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
 *    **αντίγραφο** στον υποφάκελο του φορέα και μετά διαβάζεται
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
        ocr = settings.ocrApiKey.takeIf { it.isNotBlank() }?.let { OcrSpaceClient(it) },
    )

    /** Τι βρέθηκε σε ένα παραστατικό. */
    data class Found(
        val fileName: String,
        val driveFileId: String,
        val debts: List<DebtEntity>,
        val route: DocumentText.Route = DocumentText.Route.NONE,
        val note: String = "",
    ) {
        val recognised: Boolean get() = debts.isNotEmpty()
    }

    data class Report(
        val scanned: Int,
        val skipped: Int,
        val found: List<Found>,
    ) {
        val debts: List<DebtEntity> get() = found.flatMap { it.debts }
        val unreadable: List<Found> get() = found.filterNot { it.recognised }

        /** Πώς διαβάστηκε — ενδιαφέρει τον χρήστη πριν εμπιστευτεί τα ποσά. */
        val routes: String
            get() = found.filter { it.recognised }
                .map { it.route.label }
                .distinct()
                .joinToString(", ")

        val summary: String
            get() = when {
                scanned == 0 && skipped > 0 -> "Όλα τα αρχεία έχουν ήδη διαβαστεί"
                // Η εφαρμογή βλέπει ό,τι έβαλε η ίδια στο Drive· ένα αρχείο που
                // ανέβηκε από αλλού μπορεί να μη φαίνεται, οπότε δείχνουμε τον
                // σίγουρο δρόμο αντί για ένα σκέτο «τίποτα»
                scanned == 0 -> "Δεν βρέθηκαν νέα αρχεία. Αν τα ανέβασες από " +
                    "αλλού, δοκίμασε την «Εισαγωγή αρχείου»."
                debts.isEmpty() -> "Διαβάστηκαν $scanned αρχεία, καμία οφειλή δεν αναγνωρίστηκε"
                else -> "Διαβάστηκαν $scanned αρχεία · ${debts.size} οφειλές · $routes"
            }
    }

    /**
     * Ανεβάζει αντίγραφο του αρχείου και το διαβάζει.
     *
     * Ο φορέας δίνεται από τον χρήστη γιατί καθορίζει τον φάκελο· το *είδος*
     * μέσα στον φορέα (ΙΚΑ ή ΤΕΚΑ, μισθοδοσία ή δώρο) το βρίσκει μόνο του το
     * κείμενο.
     */
    suspend fun importFile(
        agency: DebtAgency,
        fileName: String,
        bytes: ByteArray,
    ): Found = withContext(Dispatchers.IO) {
        val kind = DocumentBytes.kindOf(bytes)
            ?: error("Δέχεται PDF ή εικόνα (PNG / JPG)")
        val folder = workspace.debtsFolder(agency)
        val id = drive.upload(fileName, bytes, kind.mime, parentId = folder)
        // Καταγράφεται αμέσως ως δικό μας, ώστε να μη θεωρηθεί «ξένο» στη σάρωση
        settings.rememberDriveFiles(listOf(id))
        read(fileName, id, bytes)
    }

    /**
     * Σαρώνει τους φακέλους των οφειλών για παραστατικά που δεν έχουν διαβαστεί.
     *
     * Το [alreadyImported] έρχεται από τη βάση: ένα αρχείο διαβάζεται μία φορά,
     * αλλιώς κάθε σάρωση θα ξαναπερνούσε δεκάδες αρχεία από το OCR χωρίς λόγο.
     */
    suspend fun scan(
        alreadyImported: Set<String>,
        onProgress: (String) -> Unit = {},
    ): Report = withContext(Dispatchers.IO) {
        var scanned = 0
        var skipped = 0
        val found = mutableListOf<Found>()

        DebtAgency.entries.forEach { agency ->
            val folder = runCatching { workspace.debtsFolder(agency) }.getOrNull()
                ?: return@forEach
            val files = runCatching { workspace.documentsIn(folder) }.getOrDefault(emptyList())
            files.forEach { file ->
                if (file.id in alreadyImported) {
                    skipped++
                    return@forEach
                }
                onProgress(file.name)
                scanned++
                // Τα bytes χρειάζονται για να κριθεί αν το αρχείο έχει κείμενο
                // μέσα του, και για το ocr.space αν δεν έχει
                val bytes = runCatching { drive.download(file.id) }.getOrNull()
                runCatching { read(file.name, file.id, bytes) }
                    .getOrNull()
                    ?.let { found += it }
            }
        }

        Report(scanned = scanned, skipped = skipped, found = found)
    }

    private suspend fun read(fileName: String, driveFileId: String, bytes: ByteArray?): Found {
        val result = reader.read(bytes, driveFileId)
        return Found(
            fileName = fileName,
            driveFileId = driveFileId,
            debts = DebtParser.parse(result.text, fileName, driveFileId),
            route = result.route,
            note = result.note,
        )
    }

    /** Ο σύνδεσμος του φακέλου, για να τον ανοίξει ο χρήστης και να ρίξει αρχεία. */
    suspend fun folderUrl(): String = workspace.folderUrl(workspace.debtsFolder())
}
