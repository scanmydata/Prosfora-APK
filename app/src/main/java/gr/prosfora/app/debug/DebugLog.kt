package gr.prosfora.app.debug

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Τοπικό διαγνωστικό log για να υπάρχει πλήρης εικόνα σε αποτυχίες. */
object DebugLog {
    private const val MAX_BYTES = 512 * 1024
    private const val DUMP_LIMIT = 8000
    private val lock = Any()
    private val stamp = SimpleDateFormat("dd/MM HH:mm:ss", Locale("el"))

    private fun now(): String = synchronized(lock) { stamp.format(Date()) }

    @Volatile
    private var target: File? = null

    @Volatile
    var enabled: Boolean = true
        private set

    fun configure(context: Context, on: Boolean = true) {
        synchronized(lock) {
            enabled = on
            if (target == null) {
                val folder = File(context.applicationContext.filesDir, "diagnostics")
                runCatching { folder.mkdirs() }
                target = File(folder, FILE_NAME)
            }
        }
        if (on) line("${now()}  [logger] — καταγραφή ενεργή —")
    }

    fun file(context: Context): File =
        target ?: File(File(context.applicationContext.filesDir, "diagnostics"), FILE_NAME)

    fun log(tag: String, message: String) {
        if (!enabled) return
        line("${now()}  [$tag] $message")
    }

    inline fun log(tag: String, message: () -> String) {
        if (enabled) log(tag, message())
    }

    fun dump(tag: String, label: String, text: String) {
        if (!enabled) return
        val body = if (text.length <= DUMP_LIMIT) text else {
            text.take(DUMP_LIMIT) + "\n…(κόπηκε στους $DUMP_LIMIT από ${text.length})"
        }
        line(buildString {
            append(now()).append("  [").append(tag).append("] ")
                .append(label).append(" — ").append(text.length).append(" χαρακτήρες\n")
            append("┌──────\n")
            body.lineSequence().forEach { append("│ ").append(it).append('\n') }
            append("└──────")
        })
    }

    fun logException(tag: String, message: String, error: Throwable) {
        log(tag, "$message: ${error.stackTraceToString()}")
    }

    fun read(context: Context): String {
        val log = file(context)
        return runCatching { if (log.exists()) log.readText() else "" }.getOrDefault("")
    }

    fun clear(context: Context) {
        synchronized(lock) { runCatching { file(context).delete() } }
    }

    private fun line(text: String) {
        val log = target ?: return
        synchronized(lock) {
            runCatching {
                if (log.length() > MAX_BYTES) {
                    val kept = log.readText().takeLast(MAX_BYTES / 2)
                    log.writeText("…(παλαιότερα κόπηκαν)\n$kept")
                }
                log.appendText(text + "\n")
            }
        }
    }

    const val FILE_NAME = "prosfora.log"
}
