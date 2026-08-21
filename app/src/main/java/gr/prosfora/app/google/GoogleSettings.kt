package gr.prosfora.app.google

import android.content.Context
import gr.prosfora.app.message.MessageTemplates
import org.json.JSONObject

/**
 * Ό,τι χρειάζεται για να ξαναβρεθούν τα αρχεία στο Drive. Μόνο αναγνωριστικά —
 * κανένα μυστικό, γι' αυτό απλά SharedPreferences (τα διαπιστευτήρια SMTP είναι
 * αλλού, κρυπτογραφημένα).
 */
enum class SendMethod(val label: String, val hint: String) {
    GOOGLE(
        "Λογαριασμός Google",
        "Χωρίς κωδικό — μία έγκριση και τα email φεύγουν από τον λογαριασμό σου",
    ),
    SMTP(
        "SMTP",
        "Για δικό σου mail server ή πάροχο εκτός Google",
    ),
}

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

    /** Πώς φεύγουν τα email: με τον συνδεδεμένο λογαριασμό Google ή με SMTP. */
    var sendMethod: SendMethod
        get() = runCatching { SendMethod.valueOf(prefs.getString(KEY_SEND_METHOD, null) ?: "") }
            .getOrDefault(SendMethod.GOOGLE)
        set(value) = prefs.edit().putString(KEY_SEND_METHOD, value.name).apply()

    var senderName: String
        get() = prefs.getString(KEY_SENDER_NAME, "Γιώργος Δουραμάνης").orEmpty()
        set(value) = prefs.edit().putString(KEY_SENDER_NAME, value).apply()

    var emailSubjectTemplate: String
        get() = prefs.getString(KEY_EMAIL_SUBJECT, DEFAULT_EMAIL_SUBJECT) ?: DEFAULT_EMAIL_SUBJECT
        set(value) = prefs.edit().putString(KEY_EMAIL_SUBJECT, value).apply()

    var smsTemplate: String
        get() = prefs.getString(KEY_SMS, MessageTemplates.DEFAULT_SMS) ?: MessageTemplates.DEFAULT_SMS
        set(value) = prefs.edit().putString(KEY_SMS, value).apply()

    var viberTemplate: String
        get() = prefs.getString(KEY_VIBER, MessageTemplates.DEFAULT_VIBER) ?: MessageTemplates.DEFAULT_VIBER
        set(value) = prefs.edit().putString(KEY_VIBER, value).apply()

    /** Υποφάκελος όπου καταλήγουν τα παραγόμενα PDF. */
    var pdfFolderId: String?
        get() = prefs.getString(KEY_PDF_FOLDER, null)
        set(value) = prefs.edit().putString(KEY_PDF_FOLDER, value).apply()

    /**
     * Τα ids των φακέλων ανά έτος, ώστε να μη ρωτάμε το Drive σε κάθε PDF.
     * Αποθηκεύονται ως απλό JSON αντικείμενο «έτος → id».
     */
    fun pdfFolderForYear(year: Int): String? =
        runCatching { JSONObject(prefs.getString(KEY_PDF_YEARS, "{}").orEmpty()) }
            .getOrNull()
            ?.optString(year.toString())
            ?.takeIf { it.isNotBlank() }

    fun rememberPdfFolderForYear(year: Int, folderId: String) {
        val json = runCatching { JSONObject(prefs.getString(KEY_PDF_YEARS, "{}").orEmpty()) }
            .getOrDefault(JSONObject())
        json.put(year.toString(), folderId)
        prefs.edit().putString(KEY_PDF_YEARS, json.toString()).apply()
    }

    fun clear() = prefs.edit().clear().apply()

    companion object {
        private const val KEY_FOLDER = "folder_id"
        private const val KEY_TEMPLATE = "template_file_id"
        private const val KEY_SPREADSHEET = "spreadsheet_id"
        private const val KEY_LAST_SYNC = "last_sync_at"
        private const val KEY_AUTO_SYNC = "auto_sync"
        private const val KEY_EMAIL_BODY = "email_body"
        private const val KEY_EMAIL_SUBJECT = "email_subject"
        private const val KEY_SEND_METHOD = "send_method"
        private const val KEY_SENDER_NAME = "sender_name"
        private const val KEY_SMS = "sms_template"
        private const val KEY_VIBER = "viber_template"
        private const val KEY_PDF_FOLDER = "pdf_folder_id"
        private const val KEY_PDF_YEARS = "pdf_year_folders"

        const val DRIVE_FOLDER_NAME = "Προσφορές"
        const val TEMPLATE_NAME = "ΠΡΟΣΦΟΡΑ ΕΛΑΙΟΧΡΩΜΑΤΙΣΜΩΝ — πρότυπο"

        val DEFAULT_EMAIL_SUBJECT = MessageTemplates.DEFAULT_EMAIL_SUBJECT

        val DEFAULT_EMAIL_BODY = MessageTemplates.DEFAULT_EMAIL_BODY
    }
}
