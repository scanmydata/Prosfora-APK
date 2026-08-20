package gr.prosfora.app.doc

import android.content.Context
import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.google.DriveClient
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

        val folderId = settings.folderId
            ?: drive.findOrCreateFolder(GoogleSettings.DRIVE_FOLDER_NAME)
                .also { settings.folderId = it }

        val existing = drive.findInFolder(GoogleSettings.TEMPLATE_NAME, folderId)
        val fileId = existing?.id ?: run {
            val bundled = context.assets.open(BUNDLED_TEMPLATE).use { it.readBytes() }
            drive.upload(
                name = GoogleSettings.TEMPLATE_NAME,
                bytes = bundled,
                mimeType = DriveClient.DOCX_MIME,
                parentId = folderId,
                convertToGoogleDoc = true,
            )
        }
        settings.templateFileId = fileId
        fileId
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

        val folderId = settings.folderId
            ?: drive.findOrCreateFolder(GoogleSettings.DRIVE_FOLDER_NAME)
                .also { settings.folderId = it }

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

    fun pdfFile(context: Context, details: OfferWithDetails): File =
        File(File(context.filesDir, "documents"), "${details.offer.id}.pdf")

    /** Ο σύνδεσμος για να ανοίξει ο χρήστης το πρότυπο στο Google Docs. */
    fun templateEditUrl(templateFileId: String): String =
        "https://docs.google.com/document/d/$templateFileId/edit"

    private const val BUNDLED_TEMPLATE = "template.docx"
}
