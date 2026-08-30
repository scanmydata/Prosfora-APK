package gr.prosfora.app.google

import android.content.Context
import gr.prosfora.app.message.GreetingOptions
import gr.prosfora.app.message.GreetingStyle
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

/** Τα έτοιμα πρότυπα που έρχονται μαζί με την εφαρμογή. */
enum class BuiltInTemplate(val asset: String, val label: String, val hint: String) {
    CLASSIC(
        "template-classic.docx",
        "Κλασικό",
        "Με λογότυπα, φωτογραφίες έργων και δείγματα εργασιών",
    ),
    COMPACT(
        "template-compact.docx",
        "Συμπτυγμένο",
        "Λιτό, χωρίς εικόνες — χωράει σε μία σελίδα όπου γίνεται",
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

    var emailBodyTemplate: String
        get() = prefs.getString(KEY_EMAIL_BODY, DEFAULT_EMAIL_BODY) ?: DEFAULT_EMAIL_BODY
        set(value) = prefs.edit().putString(KEY_EMAIL_BODY, value).apply()

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

    var pdfFolderId: String?
        get() = prefs.getString(KEY_PDF_FOLDER, null)
        set(value) = prefs.edit().putString(KEY_PDF_FOLDER, value).apply()

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

    var debtsFolderId: String?
        get() = prefs.getString(KEY_DEBTS_FOLDER, null)
        set(value) = prefs.edit().putString(KEY_DEBTS_FOLDER, value).apply()

    fun debtsFolderFor(agency: String): String? =
        runCatching { JSONObject(prefs.getString(KEY_DEBTS_SUBFOLDERS, "{}").orEmpty()) }
            .getOrNull()
            ?.optString(agency)
            ?.takeIf { it.isNotBlank() }

    fun rememberDebtsFolder(agency: String, folderId: String) {
        val json = runCatching { JSONObject(prefs.getString(KEY_DEBTS_SUBFOLDERS, "{}").orEmpty()) }
            .getOrDefault(JSONObject())
        json.put(agency, folderId)
        prefs.edit().putString(KEY_DEBTS_SUBFOLDERS, json.toString()).apply()
    }

    var ocrApiKey: String
        get() = prefs.getString(KEY_OCR_KEY, DEFAULT_OCR_KEY) ?: DEFAULT_OCR_KEY
        set(value) = prefs.edit().putString(KEY_OCR_KEY, value.trim()).apply()

    var askPaidDate: Boolean
        get() = prefs.getBoolean(KEY_ASK_PAID_DATE, false)
        set(value) = prefs.edit().putBoolean(KEY_ASK_PAID_DATE, value).apply()

    var groqApiKey: String
        get() = prefs.getString(KEY_GROQ, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_GROQ, value.trim()).apply()

    var openRouterApiKey: String
        get() = prefs.getString(KEY_OPENROUTER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_OPENROUTER, value.trim()).apply()

    var debugLogging: Boolean
        get() = prefs.getBoolean(KEY_DEBUG_LOG, false)
        set(value) = prefs.edit().putBoolean(KEY_DEBUG_LOG, value).apply()

    var notifyDriveChanges: Boolean
        get() = prefs.getBoolean(KEY_NOTIFY_DRIVE, true)
        set(value) = prefs.edit().putBoolean(KEY_NOTIFY_DRIVE, value).apply()

    var knownDriveIds: Set<String>
        get() = prefs.getStringSet(KEY_KNOWN_FILES, emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_KNOWN_FILES, value.toSet()).apply()

    fun rememberDriveFiles(ids: Collection<String>) {
        if (ids.isEmpty()) return
        knownDriveIds = knownDriveIds + ids
    }

    fun forgetDriveFiles(ids: Collection<String>) {
        if (ids.isEmpty()) return
        knownDriveIds = knownDriveIds - ids.toSet()
    }

    /** Employee ids deleted permanently from the local DB. Sync must not recreate them. */
    var deletedEmployeeIds: Set<String>
        get() = prefs.getStringSet(KEY_DELETED_EMPLOYEES, emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet(KEY_DELETED_EMPLOYEES, value.toSet()).apply()

    fun rememberDeletedEmployee(id: String) {
        if (id.isBlank()) return
        deletedEmployeeIds = deletedEmployeeIds + id
    }

    fun forgetDeletedEmployee(id: String) {
        if (id.isBlank()) return
        deletedEmployeeIds = deletedEmployeeIds - id
    }

    var driveWatchReady: Boolean
        get() = prefs.getBoolean(KEY_WATCH_READY, false)
        set(value) = prefs.edit().putBoolean(KEY_WATCH_READY, value).apply()

    var statsIncludeImported: Boolean
        get() = prefs.getBoolean(KEY_STATS_IMPORTED, true)
        set(value) = prefs.edit().putBoolean(KEY_STATS_IMPORTED, value).apply()

    var builtInTemplate: BuiltInTemplate
        get() = runCatching {
            BuiltInTemplate.valueOf(prefs.getString(KEY_BUILTIN, null) ?: "")
        }.getOrDefault(BuiltInTemplate.CLASSIC)
        set(value) = prefs.edit().putString(KEY_BUILTIN, value.name).apply()

    var ownerEmail: String
        get() = prefs.getString(KEY_OWNER_EMAIL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_OWNER_EMAIL, value.trim()).apply()

    var reviewDelayDays: Int
        get() = prefs.getInt(KEY_REVIEW_DELAY, 3)
        set(value) = prefs.edit().putInt(KEY_REVIEW_DELAY, value.coerceIn(0, 365)).apply()

    var reviewLink: String
        get() = prefs.getString(KEY_REVIEW_LINK, DEFAULT_REVIEW_LINK) ?: DEFAULT_REVIEW_LINK
        set(value) = prefs.edit().putString(KEY_REVIEW_LINK, value.trim()).apply()

    var offerValidDays: Int
        get() = prefs.getInt(KEY_VALID_DAYS, 60)
        set(value) = prefs.edit().putInt(KEY_VALID_DAYS, value.coerceIn(1, 3650)).apply()

    var defaultPaymentTerms: String
        get() = prefs.getString(KEY_PAYMENT_TERMS, DEFAULT_PAYMENT_TERMS) ?: DEFAULT_PAYMENT_TERMS
        set(value) = prefs.edit().putString(KEY_PAYMENT_TERMS, value).apply()

    var reviewTemplate: String
        get() = prefs.getString(KEY_REVIEW_TEMPLATE, MessageTemplates.DEFAULT_REVIEW) ?: MessageTemplates.DEFAULT_REVIEW
        set(value) = prefs.edit().putString(KEY_REVIEW_TEMPLATE, value).apply()

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
        private const val KEY_REVIEW_DELAY = "review_delay_days"
        private const val KEY_REVIEW_LINK = "review_link"
        private const val KEY_REVIEW_TEMPLATE = "review_template"
        private const val KEY_VALID_DAYS = "offer_valid_days"
        private const val KEY_PAYMENT_TERMS = "default_payment_terms"
        private const val KEY_STATS_IMPORTED = "stats_include_imported"
        private const val KEY_BUILTIN = "builtin_template"
        private const val KEY_OWNER_EMAIL = "owner_email"
        private const val KEY_CONNECTED = "google_connected"
        private const val KEY_GREETING_STYLE = "greeting_style"
        private const val KEY_GREETING_TITLE = "greeting_title"
        private const val KEY_DEBTS_FOLDER = "debts_folder_id"
        private const val KEY_DEBTS_SUBFOLDERS = "debts_subfolders"
        private const val KEY_OCR_KEY = "ocr_api_key"
        private const val KEY_ASK_PAID_DATE = "ask_paid_date"
        private const val KEY_NOTIFY_DRIVE = "notify_drive_changes"
        private const val KEY_DEBUG_LOG = "debug_logging"
        private const val KEY_GROQ = "groq_api_key"
        private const val KEY_OPENROUTER = "openrouter_api_key"
        private const val KEY_KNOWN_FILES = "known_drive_files"
        private const val KEY_WATCH_READY = "drive_watch_ready"
        private const val KEY_DELETED_EMPLOYEES = "deleted_employee_ids"

        const val DEFAULT_OCR_KEY = "K88425303488957"
        const val DRIVE_FOLDER_NAME = "Προσφορές"
        const val DEFAULT_REVIEW_LINK =
            "https://www.google.com/search?sca_esv=faef517198de48e6&sxsrf=APpeQnsNZphqJuT4MHKHXH44GKCkHZnD4g:1787396921174&q=tovapsimo&si=APenkKm7iecQ4G6P-TsbSMFKIQtv3EFIqRAFw-i8uEbk55Z-_zMIB2TTEOESsRGZcitMJR4C6ZCfQDHpOm-TOHvLnX5KJ7--tzuev5vDfGjwf4BlCN7vN4Y%3D&uds=AJ5uw192rzALllUuaB2bJuLcuxCm6NkqFwo97LiWIr3XYWdW96aegZObi6cVFnchLgADHbfT1SmVu-TAALPaBzg-VlTWay0u-WFs88GF57hvtogFQK4pGvg&sa=X"
        const val TEMPLATE_NAME = "ΠΡΟΣΦΟΡΑ ΕΛΑΙΟΧΡΩΜΑΤΙΣΜΩΝ — πρότυπο"

        val DEFAULT_PAYMENT_TERMS = """
            20% του ποσού με την έναρξη των εργασιών
            30% με την πρόοδο των εργασιών
            30% με την πρόοδο των εργασιών
            20% με την παράδοση του έργου
        """.trimIndent()

        val DEFAULT_EMAIL_SUBJECT = MessageTemplates.DEFAULT_EMAIL_SUBJECT
        val DEFAULT_EMAIL_BODY = MessageTemplates.DEFAULT_EMAIL_BODY
    }
}