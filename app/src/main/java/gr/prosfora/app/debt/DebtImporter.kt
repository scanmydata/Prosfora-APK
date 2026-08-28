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

    /** Τι βρέθηκε σε ένα παραστατικό. */
    data class Found(
        val fileName: String,
        val driveFileId: String,
        val debts: List<DebtEntity>,
        val route: DocumentText.Route = DocumentText.Route.NONE,
        val note: String = "",
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

    /**
     * Ανεβάζει αντίγραφο του αρχείου, το διαβάζει, και το αρχειοθετεί μόνο του.
     *
     * Δεν ρωτάει τι είναι: το ίδιο το κείμενο λέει και τον φορέα και το είδος.
     * Το αρχείο ανεβαίνει πρώτα στη ρίζα των «Οφειλών» και μετακινείται στον
     * υποφάκελο που προκύπτει· αν δεν αναγνωριστεί τίποτα, μένει στη ρίζα,
     * όπου ο χρήστης θα το βρει για να το χειριστεί με το χέρι.
     */
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

        // Καταγράφεται αμέσως ως δικό μας, ώστε να μη θεωρηθεί
        // «ξένο» στη σάρωση.
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

        /**
         * Και η ρίζα: εκεί μένουν όσα δεν αναγνωρίστηκαν, κι εκεί ρίχνει
         * συνήθως ο χρήστης ό,τι κατεβάζει από τον υπολογιστή.
         */
        val folders = buildList {

            runCatching {
                workspace.debtsFolder()
            }.getOrNull()?.let {
                add(it)
            }

            DebtAgency.entries.forEach { agency ->
                runCatching {
                    workspace.debtsFolder(agency)
                }.getOrNull()?.let {
                    add(it)
                }
            }
        }

        if (folders.isEmpty()) {
            onProgress(
                "Δεν ήταν δυνατή η πρόσβαση στον φάκελο «Οφειλές».",
            )

            return@withContext Report(
                scanned = 0,
                skipped = 0,
                found = emptyList(),
            )
        }

        folders.forEach { folder ->

            val files = try {
                workspace.documentsIn(folder)
            } catch (e: Exception) {
                onProgress(
                    "Σφάλμα ανάγνωσης Drive: " +
                        "${e.message ?: "άγνωστο σφάλμα"}",
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

                /**
                 * Τα bytes χρειάζονται:
                 *
                 *  * για να κριθεί αν το PDF έχει ενσωματωμένο κείμενο
                 *  * για το ocr.space αν δεν έχει
                 */
                val bytes = try {
                    drive.download(file.id)
                } catch (e: Exception) {
                    onProgress(
                        "Αποτυχία λήψης «${file.name}»: " +
                            "${e.message ?: "άγνωστο σφάλμα"}",
                    )

                    null
                }

                try {
                    read(
                        fileName = file.name,
                        driveFileId = file.id,
                        bytes = bytes,
                    ).also {
                        found += it
                    }
                } catch (e: Exception) {
                    onProgress(
                        "Αποτυχία ανάγνωσης «${file.name}»: " +
                            "${e.message ?: "άγνωστο σφάλμα"}",
                    )
                }
            }
        }

        Report(
            scanned = scanned,
            skipped = skipped,
            found = found,
        )
    }

    private suspend fun read(
        fileName: String,
        driveFileId: String,
        bytes: ByteArray?,
    ): Found {

        /**
         * Ένας δρόμος «έπιασε» μόνο αν βγάζει οφειλή.
         * Σκέτο κείμενο δεν αρκεί: ένα χαλασμένο επίπεδο κειμένου διαβάζεται
         * μια χαρά και δεν λέει τίποτα.
         */
        val result = reader.read(
            bytes,
            driveFileId,
            fileName,
        ) { text ->
            DebtParser.parse(
                text,
                fileName,
                driveFileId,
            ).isNotEmpty()
        }

        val byRules = DebtParser.parse(
            result.text,
            fileName,
            driveFileId,
        )

        /**
         * Τοπική μεταβλητή: η ιδιότητα της κλάσης δεν κάνει smart-cast.
         */
        val model = llm

        if (
            byRules.isNotEmpty() ||
            result.text.isBlank() ||
            model == null
        ) {
            return Found(
                fileName = fileName,
                driveFileId = driveFileId,
                debts = byRules,
                route = result.route,
                note = result.note,
            )
        }

        /**
         * Οι κανόνες δεν το αναγνώρισαν αλλά κείμενο υπάρχει.
         * Πριν πει «δεν βρέθηκε τίποτα», ας το διαβάσει ένα μοντέλο.
         */
        val byModel = runCatching {
            model.extract(
                result.text,
                fileName,
                driveFileId,
            )
        }.getOrDefault(emptyList())

        return Found(
            fileName = fileName,
            driveFileId = driveFileId,
            debts = byModel,
            route = if (byModel.isEmpty()) {
                result.route
            } else {
                DocumentText.Route.LLM
            },
            note = if (byModel.isEmpty()) {
                result.note
            } else {
                "διαβάστηκε από μοντέλο"
            },
        )
    }

    /** Ο σύνδεσμος του φακέλου, για να τον ανοίξει ο χρήστης και να ρίξει αρχεία. */
    suspend fun folderUrl(): String =
        workspace.folderUrl(
            workspace.debtsFolder(),
        )
}
