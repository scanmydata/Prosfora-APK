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
 *  * ο χρήστης διαλέγει ένα PDF από το κινητό → ανεβαίνει **αντίγραφο** στον
 *    υποφάκελο του φορέα και μετά διαβάζεται
 *  * ο χρήστης έχει ήδη ρίξει PDF στους φακέλους → η σάρωση τα βρίσκει μόνη της
 *
 * Το κείμενο βγαίνει από το ίδιο το Drive (αντιγραφή ως Google Doc, εξαγωγή σε
 * text). Έτσι δεν μπαίνει βιβλιοθήκη PDF ούτε OCR μέσα στο apk, και τα σαρωμένα
 * έντυπα — όπως το σημείωμα της ΑΑΔΕ — διαβάζονται το ίδιο καλά με τα υπόλοιπα.
 *
 * Τίποτα δεν αποθηκεύεται μόνο του: το [Found] πάει στην οθόνη, ο χρήστης
 * βλέπει τι διαβάστηκε και εγκρίνει.
 */
class DebtImporter(
    private val drive: DriveClient,
    private val settings: GoogleSettings,
) {

    private val workspace = DriveWorkspace(drive, settings)

    /** Τι βρέθηκε σε ένα παραστατικό. */
    data class Found(
        val fileName: String,
        val driveFileId: String,
        val debts: List<DebtEntity>,
    ) {
        val recognised: Boolean get() = debts.isNotEmpty()
    }

    data class Report(
        val scanned: Int,
        val skipped: Int,
        val found: List<Found>,
    ) {
        val debts: List<DebtEntity> get() = found.flatMap { it.debts }
        val unreadable: List<String> get() = found.filterNot { it.recognised }.map { it.fileName }

        val summary: String
            get() = when {
                scanned == 0 -> "Δεν βρέθηκαν νέα αρχεία στους φακέλους"
                debts.isEmpty() -> "Διαβάστηκαν $scanned αρχεία, καμία οφειλή δεν αναγνωρίστηκε"
                else -> "Διαβάστηκαν $scanned αρχεία · ${debts.size} οφειλές"
            }
    }

    /**
     * Ανεβάζει αντίγραφο του αρχείου και το διαβάζει.
     *
     * Ο φορέας δίνεται από τον χρήστη γιατί καθορίζει τον φάκελο· το *είδος*
     * μέσα στον φορέα (ΙΚΑ ή ΤΕΚΑ) το βρίσκει μόνο του το κείμενο.
     */
    suspend fun importFile(
        agency: DebtAgency,
        fileName: String,
        pdf: ByteArray,
    ): Found = withContext(Dispatchers.IO) {
        val folder = workspace.debtsFolder(agency)
        val id = drive.upload(fileName, pdf, DriveClient.PDF_MIME, parentId = folder)
        read(fileName, id)
    }

    /**
     * Σαρώνει τους φακέλους των οφειλών για παραστατικά που δεν έχουν διαβαστεί.
     *
     * Το [alreadyImported] έρχεται από τη βάση: ένα αρχείο διαβάζεται μία φορά,
     * αλλιώς κάθε σάρωση θα ξαναπερνούσε δεκάδες PDF από το OCR χωρίς λόγο.
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
            val files = runCatching { workspace.pdfsIn(folder) }.getOrDefault(emptyList())
            files.forEach { file ->
                if (file.id in alreadyImported) {
                    skipped++
                    return@forEach
                }
                onProgress(file.name)
                scanned++
                runCatching { read(file.name, file.id) }
                    .getOrNull()
                    ?.let { found += it }
            }
        }

        Report(scanned = scanned, skipped = skipped, found = found)
    }

    private suspend fun read(fileName: String, driveFileId: String): Found {
        val text = runCatching { drive.readTextOf(driveFileId) }.getOrDefault("")
        return Found(
            fileName = fileName,
            driveFileId = driveFileId,
            debts = DebtParser.parse(text, fileName, driveFileId),
        )
    }

    /** Ο σύνδεσμος του φακέλου, για να τον ανοίξει ο χρήστης και να ρίξει αρχεία. */
    suspend fun folderUrl(): String = workspace.folderUrl(workspace.debtsFolder())
}
