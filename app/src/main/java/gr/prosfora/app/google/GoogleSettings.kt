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

    /**
     * Αν τα στατιστικά μετράνε και τις προσφορές που ήρθαν από εισαγωγή αρχείου.
     * Είναι παλιές επιμετρήσεις που δεν ξέρουμε σίγουρα αν έγιναν δουλειές.
     */
    var statsIncludeImported: Boolean
        get() = prefs.getBoolean(KEY_STATS_IMPORTED, true)
        set(value) = prefs.edit().putBoolean(KEY_STATS_IMPORTED, value).apply()

    /** Ποιο έτοιμο πρότυπο εγκαθίσταται όταν δεν υπάρχει άλλο στο Drive. */
    var builtInTemplate: BuiltInTemplate
        get() = runCatching {
            BuiltInTemplate.valueOf(prefs.getString(KEY_BUILTIN, null) ?: "")
        }.getOrDefault(BuiltInTemplate.CLASSIC)
        set(value) = prefs.edit().putString(KEY_BUILTIN, value.name).apply()

    /**
     * Η διεύθυνση του συνδεδεμένου λογαριασμού Google. Τη μαθαίνουμε από το
     * consent window· χρησιμεύει για να στέλνει ο χρήστης πράγματα στον εαυτό του.
     */
    var ownerEmail: String
        get() = prefs.getString(KEY_OWNER_EMAIL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_OWNER_EMAIL, value.trim()).apply()

    /** Έχει δοθεί έστω μία φορά έγκριση στη Google. */
    var googleConnected: Boolean
        get() = prefs.getBoolean(KEY_CONNECTED, false)
        set(value) = prefs.edit().putBoolean(KEY_CONNECTED, value).apply()

    /** Ξεχνάει τους φακέλους PDF ανά έτος — τους ξαναβρίσκει στον νέο χώρο εργασίας. */
    fun clearPdfFolders() = prefs.edit().remove(KEY_PDF_FOLDER).remove(KEY_PDF_YEARS).apply()

    /** Μέρες μετά την ολοκλήρωση για να ζητηθεί αξιολόγηση. */
    var reviewDelayDays: Int
        get() = prefs.getInt(KEY_REVIEW_DELAY, 3)
        set(value) = prefs.edit().putInt(KEY_REVIEW_DELAY, value.coerceIn(0, 365)).apply()

    var reviewLink: String
        get() = prefs.getString(KEY_REVIEW_LINK, DEFAULT_REVIEW_LINK) ?: DEFAULT_REVIEW_LINK
        set(value) = prefs.edit().putString(KEY_REVIEW_LINK, value.trim()).apply()

    /**
     * Πόσες μέρες ισχύει μια νέα προσφορά. Μπαίνει αυτόματα στο «ισχύει έως»
     * κάθε νέας προσφοράς — αλλάζει ελεύθερα ανά προσφορά.
     */
    var offerValidDays: Int
        get() = prefs.getInt(KEY_VALID_DAYS, 60)
        set(value) = prefs.edit().putInt(KEY_VALID_DAYS, value.coerceIn(1, 3650)).apply()

    /** Ο προεπιλεγμένος τρόπος πληρωμής — μία δόση ανά γραμμή. */
    var defaultPaymentTerms: String
        get() = prefs.getString(KEY_PAYMENT_TERMS, DEFAULT_PAYMENT_TERMS) ?: DEFAULT_PAYMENT_TERMS
        set(value) = prefs.edit().putString(KEY_PAYMENT_TERMS, value).apply()

    var reviewTemplate: String
        get() = prefs.getString(KEY_REVIEW_TEMPLATE, MessageTemplates.DEFAULT_REVIEW)
            ?: MessageTemplates.DEFAULT_REVIEW
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

        const val DRIVE_FOLDER_NAME = "Προσφορές"

        /**
         * Ο σύνδεσμος αξιολόγησης στο Google. Είναι επεξεργάσιμος από τις
         * ρυθμίσεις: οι παράμετροι που δίνει η αναζήτηση της Google παλιώνουν,
         * οπότε αν κάποτε πάψει να δουλεύει αντικαθίσταται από εκεί.
         */
        const val DEFAULT_REVIEW_LINK =
            "https://www.google.com/search?sca_esv=faef517198de48e6&sxsrf=APpeQnsNZphqJuT4MHKHXH44GKCkHZnD4g:1787396921174&q=tovapsimo&si=APenkKm7iecQ4G6P-TsbSMFKIQtv3EFIqRAFw-i8uEbk55Z-_zMIB2TTEOESsRGZcitMJR4C6ZCfQDHpOm-TOHvLnX5KJ7--tzuev5vDfGjwf4BlCN7vN4Y%3D&uds=AJ5uw192rzALllUuaB2bJuLcuxCm6NkqFwo97LiWIr3XYWdW96aegZObi6cVFnchLgADHbfT1SmVu-TAALPaBzg-VlTWay0u-WFs88GF57hvtogFQK4pGvg&sa=X"
        const val TEMPLATE_NAME = "ΠΡΟΣΦΟΡΑ ΕΛΑΙΟΧΡΩΜΑΤΙΣΜΩΝ — πρότυπο"

        /** Οι συνηθισμένες δόσεις, όπως στα υπάρχοντα φύλλα προσφοράς. */
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
