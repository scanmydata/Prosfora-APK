package gr.prosfora.app.debt

import gr.prosfora.app.google.DriveClient

/**
 * Βγάζει το κείμενο ενός παραστατικού, δοκιμάζοντας δρόμους με σειρά.
 *
 * 1. **Εξαγωγή από μέσα** — αν το PDF έχει επίπεδο κειμένου, το Drive το
 *    διαβάζει αυτούσιο. Κανένα OCR, κανένα λάθος ψηφίο.
 * 2. **OCR του Drive** — με *ανέβασμα* του αρχείου ως έγγραφο Google και
 *    `ocrLanguage=el`. Δωρεάν, ελληνικά, με τον λογαριασμό του χρήστη.
 * 3. **ocr.space** — μόνο αν υπάρχει κλειδί. Το δωρεάν επίπεδό του μπλοκάρει
 *    κατά διαστήματα (E571), οπότε δεν μπορεί να είναι ο κύριος δρόμος.
 *
 * Η σειρά έχει σημασία: η εξαγωγή είναι ακριβής, το OCR είναι εικασία. Και ο
 * ένας δρόμος δεν αρκεί — αν το κείμενο βγήκε αλλά **δεν βγάζει οφειλή**,
 * δοκιμάζεται ο επόμενος. Ένα PDF με χαλασμένο επίπεδο κειμένου αλλιώς θα
 * κατέληγε σε αδιέξοδο με ένα «καμία οφειλή δεν αναγνωρίστηκε».
 */
class DocumentText(
    private val drive: DriveClient,
    private val ocr: OcrSpaceClient?,
) {

    enum class Route(val label: String) {
        TEXT_LAYER("κείμενο από το αρχείο"),
        DRIVE_OCR("OCR (Google Drive)"),
        OCR_SPACE("OCR (ocr.space)"),
        NONE("δεν διαβάστηκε"),
    }

    data class Result(val text: String, val route: Route, val note: String = "") {
        val isEmpty: Boolean get() = text.isBlank()
    }

    /**
     * Το κείμενο του αρχείου. Το [accept] κρίνει αν ένας δρόμος «έπιασε» —
     * συνήθως «βγήκε τουλάχιστον μία οφειλή». Αν κανένας δεν το ικανοποιήσει,
     * επιστρέφεται ό,τι διαβάστηκε πρώτο, για να το δει ο χρήστης.
     */
    suspend fun read(
        bytes: ByteArray?,
        driveFileId: String,
        fileName: String = "παραστατικό",
        accept: (String) -> Boolean = { it.isNotBlank() },
    ): Result {
        val problems = mutableListOf<String>()
        var firstText: Result? = null

        routes(bytes, driveFileId, fileName).forEach { (route, read) ->
            val text = runCatching { read() }
                .onFailure { problems += "${route.label}: ${it.message}" }
                .getOrDefault("")
                .trim()

            if (text.isBlank()) return@forEach
            if (firstText == null) firstText = Result(text, route)
            if (accept(text)) return Result(text, route, problems.joinToString(" · "))
            problems += "${route.label}: δεν αναγνωρίστηκε οφειλή"
        }

        return firstText?.copy(note = problems.joinToString(" · "))
            ?: Result("", Route.NONE, problems.joinToString(" · "))
    }

    /** Οι διαθέσιμοι δρόμοι, με τη σειρά που αξίζει να δοκιμαστούν. */
    private fun routes(
        bytes: ByteArray?,
        driveFileId: String,
        fileName: String,
    ): List<Pair<Route, suspend () -> String>> = buildList {
        val hasText = bytes != null && DocumentBytes.hasTextLayer(bytes)

        // Με επίπεδο κειμένου, η απλή μετατροπή αρκεί και είναι ακριβής
        if (hasText && driveFileId.isNotBlank()) {
            add(Route.TEXT_LAYER to { drive.readTextOf(driveFileId) })
        }

        if (bytes != null) {
            val kind = DocumentBytes.kindOf(bytes)
            if (kind != null) {
                add(Route.DRIVE_OCR to { drive.readTextOf(bytes, fileName, kind.mime) })
            }
            val engine = ocr
            if (engine != null) {
                add(Route.OCR_SPACE to { engine.read(bytes, fileName) })
            }
        }

        // Χωρίς bytes —δεν κατέβηκε το αρχείο— μένει μόνο ο δρόμος του Drive
        if (bytes == null && driveFileId.isNotBlank()) {
            add(Route.DRIVE_OCR to { drive.readTextOf(driveFileId) })
        }
    }
}
