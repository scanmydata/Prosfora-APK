package gr.prosfora.app.google

/**
 * Η δομή φακέλων που φτιάχνει το app στο Drive του χρήστη.
 *
 * ```
 * Προσφορές/
 * ├── Προσφορές — βάση δεδομένων   (Google Sheet)
 * ├── ΠΡΟΣΦΟΡΑ ΕΛΑΙΟΧΡΩΜΑΤΙΣΜΩΝ — πρότυπο   (Google Doc)
 * └── PDF/                          (τα παραγόμενα αρχεία)
 * ```
 *
 * Όλα ζουν κάτω από έναν φάκελο ώστε να μοιράζεται με μία κίνηση και να μην
 * σκορπίζονται αρχεία στη ρίζα του Drive. Ο διαμοιρασμός του φακέλου δίνει
 * αυτόματα πρόσβαση και στη βάση και στα PDF.
 */
class DriveWorkspace(
    private val drive: DriveClient,
    private val settings: GoogleSettings,
) {

    /** Ο κεντρικός φάκελος· δημιουργείται την πρώτη φορά και μετά θυμάται το id. */
    suspend fun rootFolder(): String =
        settings.folderId?.takeIf { it.isNotBlank() }
            ?: drive.findOrCreateFolder(GoogleSettings.DRIVE_FOLDER_NAME)
                .also { settings.folderId = it }

    /** Υποφάκελος για τα παραγόμενα PDF, ώστε να μη μπερδεύονται με το πρότυπο. */
    suspend fun pdfFolder(): String =
        settings.pdfFolderId?.takeIf { it.isNotBlank() }
            ?: drive.findOrCreateFolder(PDF_FOLDER_NAME, rootFolder())
                .also { settings.pdfFolderId = it }

    /** Τα spreadsheets μέσα στον φάκελο — υποψήφια για κοινόχρηστη βάση. */
    suspend fun spreadsheetsInFolder(): List<DriveClient.DriveFile> {
        val root = rootFolder()
        return drive.list(
            "mimeType='$SHEET_MIME' and '$root' in parents and trashed=false",
        )
    }

    /** Όλα τα spreadsheets στα οποία έχει πρόσβαση το app, όπου κι αν βρίσκονται. */
    suspend fun allVisibleSpreadsheets(): List<DriveClient.DriveFile> =
        drive.list("mimeType='$SHEET_MIME' and trashed=false")

    fun folderUrl(folderId: String) = "https://drive.google.com/drive/folders/$folderId"

    companion object {
        const val PDF_FOLDER_NAME = "PDF"
        const val SHEET_MIME = "application/vnd.google-apps.spreadsheet"
    }
}
