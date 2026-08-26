package gr.prosfora.app.debt

import gr.prosfora.app.google.DriveClient

/**
 * Βγάζει το κείμενο ενός παραστατικού, με σειρά προτεραιότητας.
 *
 * 1. **Εξαγωγή από μέσα** — αν το PDF έχει επίπεδο κειμένου, το Drive το
 *    διαβάζει αυτούσιο. Κανένα OCR, κανένα λάθος ψηφίο.
 * 2. **ocr.space** — για σαρωμένα έντυπα και στιγμιότυπα οθόνης.
 * 3. **OCR του Drive** — δίχτυ ασφαλείας όταν το ocr.space αρνηθεί ή λείπει κλειδί.
 *
 * Η σειρά έχει σημασία γιατί τα δύο πρώτα δίνουν διαφορετικής ποιότητας
 * αποτέλεσμα: η εξαγωγή είναι ακριβής, το OCR είναι εικασία. Ο δρόμος που
 * ακολουθήθηκε επιστρέφεται μαζί, για να τον δει ο χρήστης πριν αποθηκεύσει.
 */
class DocumentText(
    private val drive: DriveClient,
    private val ocr: OcrSpaceClient?,
) {

    enum class Route(val label: String) {
        TEXT_LAYER("κείμενο από το αρχείο"),
        OCR_SPACE("OCR (ocr.space)"),
        DRIVE_OCR("OCR (Google Drive)"),
        NONE("δεν διαβάστηκε"),
    }

    data class Result(val text: String, val route: Route, val note: String = "") {
        val isEmpty: Boolean get() = text.isBlank()
    }

    suspend fun read(bytes: ByteArray?, driveFileId: String): Result {
        val problems = mutableListOf<String>()

        if (bytes != null && DocumentBytes.hasTextLayer(bytes)) {
            val text = driveText(driveFileId)
            if (text.isNotBlank()) return Result(text, Route.TEXT_LAYER)
            problems += "η εξαγωγή κειμένου δεν απέδωσε"
        }

        if (bytes != null && ocr != null) {
            runCatching { ocr.read(bytes, "παραστατικό") }
                .onSuccess { return Result(it, Route.OCR_SPACE) }
                .onFailure { problems += it.message.orEmpty() }
        }

        val fallback = driveText(driveFileId)
        if (fallback.isNotBlank()) {
            return Result(fallback, Route.DRIVE_OCR, problems.joinToString(" · "))
        }
        return Result("", Route.NONE, problems.joinToString(" · "))
    }

    /**
     * Το Drive μετατρέπει το αρχείο σε έγγραφο Google και μας δίνει το κείμενο.
     * Όταν υπάρχει επίπεδο κειμένου το διαβάζει αυτούσιο· αλλιώς κάνει OCR.
     */
    private suspend fun driveText(fileId: String): String =
        runCatching { drive.readTextOf(fileId) }.getOrDefault("").trim()
}
