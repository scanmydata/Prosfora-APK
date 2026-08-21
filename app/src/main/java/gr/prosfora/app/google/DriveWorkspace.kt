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

    /**
     * Υποφάκελος ανά έτος έκδοσης: `PDF/2026`. Με δεκάδες προσφορές τον χρόνο,
     * ένας ενιαίος φάκελος γίνεται αχρησιμοποίητος.
     */
    suspend fun pdfFolderForYear(year: Int): String =
        settings.pdfFolderForYear(year)
            ?: drive.findOrCreateFolder(year.toString(), pdfFolder())
                .also { settings.rememberPdfFolderForYear(year, it) }

    /** Τα έτη που υπάρχουν ήδη ως υποφάκελοι, νεότερο πρώτα. */
    suspend fun pdfYears(): List<DriveClient.DriveFile> {
        val parent = pdfFolder()
        return drive.list("mimeType='${DriveClient.FOLDER_MIME}' and '$parent' in parents and trashed=false")
            .sortedByDescending { it.name }
    }

    suspend fun pdfsInYear(yearFolderId: String): List<DriveClient.DriveFile> =
        drive.list("mimeType='${DriveClient.PDF_MIME}' and '$yearFolderId' in parents and trashed=false")

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
