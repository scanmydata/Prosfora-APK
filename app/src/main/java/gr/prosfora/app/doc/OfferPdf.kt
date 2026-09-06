package gr.prosfora.app.doc

import android.content.Context
import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.google.DriveClient
import gr.prosfora.app.google.DriveWorkspace
import gr.prosfora.app.google.BuiltInTemplate
import gr.prosfora.app.google.GoogleSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Παράγει το PDF μιας προσφοράς από το πρότυπο που ζει στο Drive.
 *
 * Ροή — ίδια λογική με το MakeDoc του AppSheet, χωρίς sensitive scopes:
 * ```
 * 1. export του Google Doc προτύπου ως .docx
 * 2. αντικατάσταση placeholders τοπικά (DocxTemplate)
 * 3. upload με μετατροπή σε Google Doc (προσωρινό)
 * 4. export ως PDF
 * 5. διαγραφή του προσωρινού — το Drive μένει καθαρό
 * ```
 * Το πρότυπο δεν αγγίζεται ποτέ: ο χρήστης το επεξεργάζεται ελεύθερα στο Google Docs
 * και η επόμενη προσφορά βγαίνει με τις αλλαγές του.
 */
object OfferPdf {

    /** Το πρότυπο στο Drive· αν λείπει, ανεβαίνει το ενσωματωμένο .docx μία φορά. */
    suspend fun ensureTemplate(
        context: Context,
        drive: DriveClient,
        settings: GoogleSettings,
    ): String = withContext(Dispatchers.IO) {
        settings.templateFileId?.let { return@withContext it }

        val folderId = DriveWorkspace(drive, settings).rootFolder()

        val existing = drive.findInFolder(GoogleSettings.TEMPLATE_NAME, folderId)
        val fileId = existing?.id ?: installBuiltIn(context, drive, settings)
        settings.templateFileId = fileId
        fileId
    }

    /**
     * Ανεβάζει στο Drive ένα από τα έτοιμα πρότυπα, αντικαθιστώντας ό,τι υπάρχει.
     *
     * Το προηγούμενο διαγράφεται ώστε να μη μείνουν δύο αρχεία με το ίδιο όνομα
     * στον φάκελο — και να μη διαλέξει το Drive λάθος την επόμενη φορά.
     */
    suspend fun installBuiltIn(
        context: Context,
        drive: DriveClient,
        settings: GoogleSettings,
        choice: BuiltInTemplate = settings.builtInTemplate,
    ): String = withContext(Dispatchers.IO) {
        // Το πρότυπο περνάει από την ίδια διάταξη συνόλων που τυπώνεται, ώστε
        // αυτό που εγκαθίσταται στο Drive να είναι αυτό που βλέπει ο πελάτης
        val bundled = DocxTemplate.withFullTotals(
            context.assets.open(choice.asset).use { it.readBytes() },
        )
        val folderId = DriveWorkspace(drive, settings).rootFolder()

        settings.templateFileId?.let { old -> runCatching { drive.delete(old) } }
        settings.templateFileId = null

        drive.upload(
            name = GoogleSettings.TEMPLATE_NAME,
            bytes = bundled,
            mimeType = DriveClient.DOCX_MIME,
            parentId = folderId,
            convertToGoogleDoc = true,
        ).also {
            settings.templateFileId = it
            settings.builtInTemplate = choice
        }
    }

    /**
     * Ανεβάζει πρότυπο που έφερε ο χρήστης. Το αρχείο του **δεν αγγίζεται**:
     * διαβάζονται τα bytes, προσαρμόζονται για A4 και ανεβαίνει αντίγραφο.
     */
    suspend fun installFromBytes(
        drive: DriveClient,
        settings: GoogleSettings,
        docx: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        require(DocxPrintLayout.looksLikeDocx(docx)) {
            "Το αρχείο δεν είναι έγγραφο Word (.docx)"
        }
        val prepared = DocxPrintLayout.normalize(docx)
        val folderId = DriveWorkspace(drive, settings).rootFolder()

        settings.templateFileId?.let { old -> runCatching { drive.delete(old) } }
        settings.templateFileId = null

        drive.upload(
            name = GoogleSettings.TEMPLATE_NAME,
            bytes = prepared,
            mimeType = DriveClient.DOCX_MIME,
            parentId = folderId,
            convertToGoogleDoc = true,
        ).also { settings.templateFileId = it }
    }

    /** Κατεβάζει το τρέχον πρότυπο ως .docx bytes. */
    suspend fun fetchTemplateDocx(
        context: Context,
        drive: DriveClient,
        settings: GoogleSettings,
    ): ByteArray = withContext(Dispatchers.IO) {
        val templateId = ensureTemplate(context, drive, settings)
        drive.export(templateId, DriveClient.DOCX_MIME)
    }

    /**
     * Παράγει το PDF και το γράφει στον ιδιωτικό χώρο του app.
     * Το αρχείο δίνεται μετά ως συνημμένο ή σε προεπισκόπηση.
     */
    suspend fun generate(
        context: Context,
        drive: DriveClient,
        settings: GoogleSettings,
        details: OfferWithDetails,
        keepInDrive: Boolean = true,
    ): File = withContext(Dispatchers.IO) {
        val templateDocx = fetchTemplateDocx(context, drive, settings)
        val rendered = DocxTemplate.render(templateDocx, details)

        val workspace = DriveWorkspace(drive, settings)
        val folderId = workspace.pdfFolderForYear(details.year)

        val documentName = fileBaseName(details)
        // Το Drive κάνει τη μετατροπή σε PDF — δεν χρειαζόμαστε renderer στη συσκευή
        val tempDocId = drive.upload(
            name = if (keepInDrive) documentName else "$documentName (προσωρινό)",
            bytes = rendered,
            mimeType = DriveClient.DOCX_MIME,
            parentId = folderId,
            convertToGoogleDoc = true,
        )

        try {
            val pdfBytes = drive.export(tempDocId, DriveClient.PDF_MIME)
            val target = pdfFile(context, details)
            target.parentFile?.mkdirs()
            target.writeBytes(pdfBytes)
            dropLocalCopiesExcept(context, details, target)

            if (keepInDrive) {
                // Το PDF μένει και στο Drive, όπως έκανε το AppSheet
                val uploaded = drive.upload(
                    name = "$documentName.pdf",
                    bytes = pdfBytes,
                    mimeType = DriveClient.PDF_MIME,
                    parentId = folderId,
                )
                // Πρώτα ανεβαίνει το καινούργιο και μετά φεύγει το παλιό: αν
                // κοπεί το δίκτυο στη μέση, ο φάκελος μένει με δύο αρχεία και
                // όχι με κανένα.
                replacePrevious(drive, settings, details, folderId, documentName, uploaded)
                settings.rememberPdfFile(details.offer.id, uploaded)
            }
            target
        } finally {
            runCatching { drive.delete(tempDocId) }
        }
    }

    /**
     * Τα τοπικά PDF είναι οργανωμένα ανά έτος. Αν αλλάξει η ημερομηνία της
     * προσφοράς, το καινούργιο γράφεται σε άλλον φάκελο και το παλιό έμενε
     * πίσω — δύο αρχεία για την ίδια προσφορά μέσα στο τοπικό αρχείο.
     */
    private fun dropLocalCopiesExcept(context: Context, details: OfferWithDetails, keep: File) {
        val name = "${details.offer.id}.pdf"
        localArchiveRoot(context).walkTopDown()
            .filter { it.isFile && it.name == name && it != keep }
            .forEach { stale -> runCatching { stale.delete() } }
    }

    /**
     * Σβήνει το προηγούμενο PDF της ίδιας προσφοράς.
     *
     * Δύο πηγές, γιατί καμία δεν αρκεί μόνη της. Το **όνομα** πιάνει και όσα
     * ανέβασε άλλος χρήστης ή παλιότερη έκδοση της εφαρμογής, αλλά χάνεται μόλις
     * αλλάξει η διεύθυνση της προσφοράς. Το **αποθηκευμένο id** αντέχει τη
     * μετονομασία, αλλά το ξέρει μόνο η συσκευή που παρήγαγε το αρχείο.
     *
     * Οι αποτυχίες αγνοούνται επίτηδες: το καινούργιο PDF έχει ήδη ανέβει, και
     * ένα ορφανό αρχείο στο Drive δεν είναι λόγος να αποτύχει η αποστολή.
     */
    private suspend fun replacePrevious(
        drive: DriveClient,
        settings: GoogleSettings,
        details: OfferWithDetails,
        folderId: String,
        documentName: String,
        keep: String,
    ) {
        val sameName = runCatching {
            drive.filesNamed("$documentName.pdf", folderId).map { it.id }
        }.getOrDefault(emptyList())

        val remembered = settings.pdfFileFor(details.offer.id)
        (sameName + listOfNotNull(remembered))
            .distinct()
            .filterNot { it == keep }
            .forEach { old -> runCatching { drive.delete(old) } }
    }

    fun fileBaseName(details: OfferWithDetails): String =
        "ΠΡΟΣΦΟΡΑ ΕΛΑΙΟΧΡΩΜΑΤΙΣΜΩΝ ${details.offer.address}".trim().replace('/', '-')

    /**
     * Τοπική θέση του PDF, οργανωμένη κι αυτή ανά έτος. Οι παλιότερες εκδόσεις
     * έγραφαν κατευθείαν στο `documents/`, οπότε ελέγχεται και εκείνη η θέση.
     */
    fun pdfFile(context: Context, details: OfferWithDetails): File {
        val root = File(context.filesDir, "documents")
        val current = File(File(root, details.year.toString()), "${details.offer.id}.pdf")
        if (current.exists()) return current
        val legacy = File(root, "${details.offer.id}.pdf")
        return if (legacy.exists()) legacy else current
    }

    /**
     * Πότε άλλαξε τελευταία φορά κάτι που τυπώνεται: η ίδια η προσφορά, οι
     * χώροι της ή οι παρατηρήσεις της.
     */
    private fun lastChangedAt(details: OfferWithDetails): Long = maxOf(
        details.offer.updatedAt,
        details.spaces.maxOfOrNull { it.updatedAt } ?: 0L,
        details.notes.maxOfOrNull { it.updatedAt } ?: 0L,
    )

    /**
     * Το αποθηκευμένο PDF είναι ξεπερασμένο.
     *
     * Χωρίς αυτόν τον έλεγχο, μια προσφορά που είχε ήδη PDF στελνόταν με το
     * **παλιό** αρχείο: το app έβλεπε ότι υπάρχει και δεν το ξανάφτιαχνε. Κάθε
     * διόρθωση τιμής ή χώρου έφευγε στον πελάτη αόρατη.
     */
    fun isStale(context: Context, details: OfferWithDetails): Boolean {
        val file = pdfFile(context, details)
        if (!file.exists()) return true
        return lastChangedAt(details) > file.lastModified()
    }

    /** Το τοπικό PDF, μόνο αν είναι ενημερωμένο. */
    fun freshPdf(context: Context, details: OfferWithDetails): File? =
        pdfFile(context, details).takeIf { it.exists() && !isStale(context, details) }

    /** Ο ριζικός φάκελος των τοπικών PDF, για την οθόνη αρχείου. */
    fun localArchiveRoot(context: Context): File = File(context.filesDir, "documents")

    /** Ο σύνδεσμος για να ανοίξει ο χρήστης το πρότυπο στο Google Docs. */
    fun templateEditUrl(templateFileId: String): String =
        "https://docs.google.com/document/d/$templateFileId/edit"
}
