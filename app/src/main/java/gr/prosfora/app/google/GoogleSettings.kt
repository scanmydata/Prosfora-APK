package gr.prosfora.app.google

import android.content.Context

/**
 * Ό,τι χρειάζεται για να ξαναβρεθούν τα αρχεία στο Drive. Μόνο αναγνωριστικά —
 * κανένα μυστικό, γι' αυτό απλά SharedPreferences (τα διαπιστευτήρια SMTP είναι
 * αλλού, κρυπτογραφημένα).
 */
class GoogleSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("google_settings", Context.MODE_PRIVATE)

    /** Ο φάκελος «Προσφορές» στο Drive του χρήστη. */
    var folderId: String?
        get() = prefs.getString(KEY_FOLDER, null)
        set(value) = prefs.edit().putString(KEY_FOLDER, value).apply()

    /** Το Google Doc πρότυπο — αυτό που ο χρήστης επεξεργάζεται ελεύθερα. */
    var templateFileId: String?
        get() = prefs.getString(KEY_TEMPLATE, null)
        set(value) = prefs.edit().putString(KEY_TEMPLATE, value).apply()

    /** Το κοινόχρηστο Google Sheet που παίζει τον ρόλο της βάσης. */
    var spreadsheetId: String?
        get() = prefs.getString(KEY_SPREADSHEET, null)
        set(value) = prefs.edit().putString(KEY_SPREADSHEET, value).apply()

    var lastSyncAt: Long
        get() = prefs.getLong(KEY_LAST_SYNC, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_SYNC, value).apply()

    var autoSync: Boolean
        get() = prefs.getBoolean(KEY_AUTO_SYNC, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_SYNC, value).apply()

    /** Το σώμα του email, επεξεργάσιμο από τον χρήστη. `{είδος}` αντικαθίσταται. */
    var emailBodyTemplate: String
        get() = prefs.getString(KEY_EMAIL_BODY, DEFAULT_EMAIL_BODY) ?: DEFAULT_EMAIL_BODY
        set(value) = prefs.edit().putString(KEY_EMAIL_BODY, value).apply()

    var emailSubjectTemplate: String
        get() = prefs.getString(KEY_EMAIL_SUBJECT, DEFAULT_EMAIL_SUBJECT) ?: DEFAULT_EMAIL_SUBJECT
        set(value) = prefs.edit().putString(KEY_EMAIL_SUBJECT, value).apply()

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val KEY_FOLDER = "folder_id"
        private const val KEY_TEMPLATE = "template_file_id"
        private const val KEY_SPREADSHEET = "spreadsheet_id"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_EMAIL_BODY = "email_body"
        private const val KEY_EMAIL_SUBJECT = "email_subject"

        const val DRIVE_FOLDER_NAME = "Προσφορές"
        const val TEMPLATE_NAME = "ΠΡΟΣΦΟΡΑ ΕΛΑΙΟΧΡΩΜΑΤΙΣΜΩΝ — πρότυπο"

        const val DEFAULT_EMAIL_SUBJECT = "Προσφορά ελαιοχρωματισμών {διεύθυνση}"

        val DEFAULT_EMAIL_BODY = """
            Καλησπέρα,

            Σας αποστέλλω την προσφορά για το χρωματισμό της {είδος} σας.
            Στη διάθεσή σας για οποιαδήποτε επιπλέον πληροφορία χρειαστείτε.
        """.trimIndent()
    }
}
