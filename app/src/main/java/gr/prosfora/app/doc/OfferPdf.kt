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
        val bundled = context.assets.open(choice.asset).use { it.readBytes() }
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

            if (keepInDrive) {
                // Το PDF μένει και στο Drive, όπως έκανε το AppSheet
                drive.upload(
                    name = "$documentName.pdf",
                    bytes = pdfBytes,
                    mimeType = DriveClient.PDF_MIME,
                    parentId = folderId,
                )
            }
            target
        } finally {
            runCatching { drive.delete(tempDocId) }
        }
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

    /** Ο ριζικός φάκελος των τοπικών PDF, για την οθόνη αρχείου. */
    fun localArchiveRoot(context: Context): File = File(context.filesDir, "documents")

    /** Ο σύνδεσμος για να ανοίξει ο χρήστης το πρότυπο στο Google Docs. */
    fun templateEditUrl(templateFileId: String): String =
        "https://docs.google.com/document/d/$templateFileId/edit"
}
