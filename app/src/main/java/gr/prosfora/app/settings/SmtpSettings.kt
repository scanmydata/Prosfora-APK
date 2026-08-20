package gr.prosfora.app.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class SmtpSettings(
    val host: String = "",
    val port: Int = 587,
    val username: String = "",
    val password: String = "",
    val useStartTls: Boolean = true,
    val useSsl: Boolean = false,
    val fromAddress: String = "",
    val fromName: String = "Γιώργος Δουραμάνης",
    /** Υπογραφή που μπαίνει στο τέλος του email */
    val signature: String = DEFAULT_SIGNATURE,
) {
    val isConfigured: Boolean
        get() = host.isNotBlank() && username.isNotBlank() && password.isNotBlank() &&
            fromAddress.isNotBlank()

    companion object {
        const val DEFAULT_SIGNATURE = "Με εκτίμηση,\nΓιώργος Δουραμάνης\n6945773605"
    }
}

/**
 * Τα διαπιστευτήρια SMTP μένουν στη συσκευή, κρυπτογραφημένα με κλειδί που ζει
 * στο Android Keystore. Δεν φεύγουν ποτέ από το κινητό και δεν μπαίνουν στο backup.
 */
class SmtpSettingsStore(context: Context) {

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "smtp_settings",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun load(): SmtpSettings = SmtpSettings(
        host = prefs.getString(KEY_HOST, "").orEmpty(),
        port = prefs.getInt(KEY_PORT, 587),
        username = prefs.getString(KEY_USER, "").orEmpty(),
        password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
        useStartTls = prefs.getBoolean(KEY_STARTTLS, true),
        useSsl = prefs.getBoolean(KEY_SSL, false),
        fromAddress = prefs.getString(KEY_FROM, "").orEmpty(),
        fromName = prefs.getString(KEY_FROM_NAME, "Γιώργος Δουραμάνης").orEmpty(),
        signature = prefs.getString(KEY_SIGNATURE, SmtpSettings.DEFAULT_SIGNATURE)
            .orEmpty().ifBlank { SmtpSettings.DEFAULT_SIGNATURE },
    )

    fun save(settings: SmtpSettings) {
        prefs.edit()
            .putString(KEY_HOST, settings.host.trim())
            .putInt(KEY_PORT, settings.port)
            .putString(KEY_USER, settings.username.trim())
            .putString(KEY_PASSWORD, settings.password)
            .putBoolean(KEY_STARTTLS, settings.useStartTls)
            .putBoolean(KEY_SSL, settings.useSsl)
            .putString(KEY_FROM, settings.fromAddress.trim())
            .putString(KEY_FROM_NAME, settings.fromName.trim())
            .putString(KEY_SIGNATURE, settings.signature)
            .apply()
    }

    private companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_USER = "username"
        const val KEY_PASSWORD = "password"
        const val KEY_STARTTLS = "starttls"
        const val KEY_SSL = "ssl"
        const val KEY_FROM = "from"
        const val KEY_FROM_NAME = "from_name"
        const val KEY_SIGNATURE = "signature"
    }
}

/** Έτοιμες ρυθμίσεις για τους συνηθισμένους παρόχους. */
data class SmtpPreset(val label: String, val host: String, val port: Int, val startTls: Boolean, val ssl: Boolean, val hint: String)

val SMTP_PRESETS = listOf(
    SmtpPreset("Gmail", "smtp.gmail.com", 587, startTls = true, ssl = false,
        hint = "Χρειάζεται App Password (με 2FA ενεργό), όχι ο κανονικός κωδικός"),
    SmtpPreset("Outlook / Hotmail", "smtp-mail.outlook.com", 587, startTls = true, ssl = false,
        hint = "Χρειάζεται app password αν έχεις 2FA"),
    SmtpPreset("Yahoo", "smtp.mail.yahoo.com", 465, startTls = false, ssl = true,
        hint = "Χρειάζεται app password"),
)
