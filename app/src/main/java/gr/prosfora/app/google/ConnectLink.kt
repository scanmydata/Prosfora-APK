package gr.prosfora.app.google

import android.net.Uri

/**
 * Ο σύνδεσμος που ρυθμίζει την εφαρμογή ενός συνεργάτη με ένα πάτημα.
 *
 * Όταν ο ιδιοκτήτης μοιράζεται τον φάκελο, ο συνεργάτης παίρνει πρόσβαση στο
 * Drive — αλλά η εφαρμογή του δεν ξέρει *ποιο* Sheet και ποιον φάκελο να
 * χρησιμοποιήσει. Χωρίς αυτό θα έπρεπε να του υπαγορεύσει κανείς αναγνωριστικά
 * αρχείων στο τηλέφωνο.
 *
 * Ο σύνδεσμος δεν είναι μυστικό και δεν δίνει καμία πρόσβαση από μόνος του:
 * μεταφέρει μόνο αναγνωριστικά. Η πρόσβαση κρίνεται αποκλειστικά από τα
 * δικαιώματα του Google Drive — αν δεν έχει προσκληθεί, ο σύνδεσμος δεν του
 * ανοίγει τίποτα.
 */
object ConnectLink {

    const val SCHEME = "prosfora"
    const val HOST = "connect"

    /** Η σελίδα προορισμού: δουλεύει και σε υπολογιστή, και προωθεί στην εφαρμογή. */
    private const val LANDING = "https://scanmydata.github.io/Prosfora-APK/connect.html"

    data class Invite(
        val spreadsheetId: String?,
        val folderId: String?,
        val templateId: String?,
        val from: String?,
    ) {
        val isUsable: Boolean get() = spreadsheetId != null || folderId != null
    }

    /**
     * Διαβάζει το intent που άνοιξε την εφαρμογή. Δέχεται και τη μορφή
     * `prosfora://connect?…` και τη σελίδα `https://…/connect.html?…`, ώστε να
     * παίζει είτε πατηθεί απευθείας είτε μέσω του browser.
     */
    fun parse(uri: Uri?): Invite? {
        if (uri == null) return null
        val matches = (uri.scheme == SCHEME && uri.host == HOST) ||
            (uri.scheme?.startsWith("http") == true && uri.path?.contains(HOST) == true)
        if (!matches) return null

        val invite = Invite(
            spreadsheetId = uri.getQueryParameter("sheet")?.takeIf { it.isNotBlank() },
            folderId = uri.getQueryParameter("folder")?.takeIf { it.isNotBlank() },
            templateId = uri.getQueryParameter("template")?.takeIf { it.isNotBlank() },
            from = uri.getQueryParameter("from")?.takeIf { it.isNotBlank() },
        )
        return invite.takeIf { it.isUsable }
    }

    /** Ο σύνδεσμος που μπαίνει στο email της πρόσκλησης. */
    fun build(
        spreadsheetId: String?,
        folderId: String?,
        templateId: String?,
        from: String,
    ): String = Uri.parse(LANDING).buildUpon().apply {
        spreadsheetId?.let { appendQueryParameter("sheet", it) }
        folderId?.let { appendQueryParameter("folder", it) }
        templateId?.let { appendQueryParameter("template", it) }
        if (from.isNotBlank()) appendQueryParameter("from", from)
    }.build().toString()

    /** Εφαρμόζει την πρόσκληση στις τοπικές ρυθμίσεις. */
    fun applyTo(settings: GoogleSettings, invite: Invite) {
        invite.spreadsheetId?.let { settings.spreadsheetId = it }
        invite.folderId?.let { settings.folderId = it }
        invite.templateId?.let { settings.templateFileId = it }
        // Οι φάκελοι PDF ανά έτος ανήκουν στον προηγούμενο χώρο εργασίας και
        // ξαναβρίσκονται μόνοι τους μέσα στον νέο ριζικό φάκελο
        settings.clearPdfFolders()
    }
}
