package gr.prosfora.app.debt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * OCR μέσω του ocr.space.
 *
 * Χρησιμοποιείται όταν το παραστατικό δεν έχει κείμενο μέσα του — σαρωμένο
 * έγγραφο ή στιγμιότυπο οθόνης. Το κλειδί είναι του χρήστη και αλλάζει από τις
 * ρυθμίσεις· το αρχείο φεύγει στον πάροχο, οπότε είναι συνειδητή επιλογή.
 *
 * **Το κλειδί πάει σε HTTP header**, όχι σε πεδίο της φόρμας: έτσι το ορίζει η
 * τεκμηρίωση, και ένα κλειδί σε λάθος θέση γυρίζει σαν σφάλμα υπηρεσίας αντί
 * για σαφές «λάθος κλειδί». Στέλνεται και στα δύο, γιατί δεν κοστίζει τίποτα.
 *
 * **Μηχανή 3** πρώτα: είναι αυτή που δίνει σωστό αποτέλεσμα στα ελληνικά έντυπα
 * και στα στιγμιότυπα οθόνης. Αν αρνηθεί, δοκιμάζονται η 1 με ρητά ελληνικά και
 * η 2. Τα 503 και τα προσωρινά σφάλματα ξαναδοκιμάζονται: η υπηρεσία τα βγάζει
 * κατά διαστήματα και μια δεύτερη προσπάθεια συνήθως περνάει.
 */
class OcrSpaceClient(private val apiKey: String) {

    class Failure(message: String) : IllegalStateException(message)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    /** Μηχανή και γλώσσα μαζί: η 1 θέλει ρητά ελληνικά, οι άλλες αναγνωρίζουν μόνες. */
    private data class Attempt(val engine: Int, val language: String)

    private val attempts = listOf(
        Attempt(3, "auto"),
        Attempt(1, "gre"),
        Attempt(2, "auto"),
    )

    /**
     * Το κείμενο του αρχείου. Ρίχνει [Failure] με το μήνυμα του παρόχου, ώστε ο
     * καλών να αποφασίσει αν θα δοκιμάσει άλλον δρόμο.
     */
    suspend fun read(bytes: ByteArray, fileName: String): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Δεν έχει οριστεί κλειδί OCR" }
        val kind = DocumentBytes.kindOf(bytes)
            ?: throw Failure("Δεν αναγνωρίστηκε η μορφή του αρχείου")
        if (bytes.size > MAX_BYTES) {
            throw Failure(
                "Το αρχείο είναι ${bytes.size / 1024} KB — το δωρεάν κλειδί " +
                    "δέχεται ως 1 MB",
            )
        }

        val problems = mutableListOf<String>()
        attempts.forEach { attempt ->
            runCatching { withRetry(bytes, fileName, kind, attempt) }
                .onSuccess { return@withContext it }
                .onFailure { problems += "μηχανή ${attempt.engine}: ${it.message}" }
        }
        throw Failure(problems.joinToString(" · "))
    }

    /** Μια γρήγορη κλήση με μικρή εικόνα, για να φανεί αν το κλειδί δουλεύει. */
    suspend fun check(): String = withContext(Dispatchers.IO) {
        val text = withRetry(PROBE_PNG, "probe.png", DocumentBytes.Kind.PNG, attempts.first())
        text.ifBlank { "—" }
    }

    private suspend fun withRetry(
        bytes: ByteArray,
        fileName: String,
        kind: DocumentBytes.Kind,
        attempt: Attempt,
    ): String {
        var last: Exception? = null
        repeat(RETRIES) { round ->
            try {
                return post(bytes, fileName, kind, attempt)
            } catch (transient: Transient) {
                last = transient
                // Γεωμετρική αναμονή: 1s, 3s. Το 503 του ocr.space περνάει μόνο του
                delay(1000L * (1 + round * 2))
            } catch (fatal: Failure) {
                throw fatal
            }
        }
        throw Failure(last?.message ?: "άγνωστο σφάλμα")
    }

    /** Σφάλμα που αξίζει δεύτερη προσπάθεια — σε αντίθεση με το λάθος κλειδί. */
    private class Transient(message: String) : IllegalStateException(message)

    private fun post(
        bytes: ByteArray,
        fileName: String,
        kind: DocumentBytes.Kind,
        attempt: Attempt,
    ): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("apikey", apiKey)
            .addFormDataPart("OCREngine", attempt.engine.toString())
            .addFormDataPart("language", attempt.language)
            .addFormDataPart("filetype", kind.ocrType)
            .addFormDataPart("isOverlayRequired", "false")
            .addFormDataPart("detectOrientation", "true")
            .addFormDataPart("scale", "true")
            .addFormDataPart("file", fileName, bytes.toRequestBody(kind.mime.toMediaType()))
            .build()

        val request = Request.Builder()
            .url(ENDPOINT)
            // Η τεκμηριωμένη θέση του κλειδιού
            .header("apikey", apiKey)
            .post(body)
            .build()

        http.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = messageIn(payload).ifBlank { payload.take(160) }
                val text = "ocr.space ${response.code}${if (detail.isBlank()) "" else ": $detail"}"
                // 429 = ξεπεράστηκε ο ρυθμός, 5xx = δικό τους πρόβλημα
                if (response.code == 429 || response.code >= 500) throw Transient(text)
                throw Failure(text)
            }
            return parse(payload)
        }
    }

    private fun parse(payload: String): String {
        val json = runCatching { JSONObject(payload) }
            .getOrElse { throw Failure("Ακατανόητη απάντηση από το ocr.space") }

        val exit = json.optInt("OCRExitCode", 0)
        if (json.optBoolean("IsErroredOnProcessing") || exit >= 3) {
            val message = messageIn(payload)
            // E101 λήξη χρόνου, E208 εσωτερικό σφάλμα — και τα δύο περνάνε με επανάληψη
            if (message.contains("E101") || message.contains("E208")) throw Transient(message)
            throw Failure(message.ifBlank { "Το ocr.space δεν διάβασε κείμενο" })
        }

        val results = json.optJSONArray("ParsedResults")
            ?: throw Failure(messageIn(payload).ifBlank { "Το ocr.space δεν επέστρεψε κείμενο" })

        val text = (0 until results.length())
            .joinToString("\n") { results.getJSONObject(it).optString("ParsedText") }
            .trim()
        if (text.isBlank()) throw Failure("Το ocr.space δεν διάβασε κείμενο")
        return text
    }

    /** Το μήνυμα λάθους, όπου κι αν το βάλει η υπηρεσία. */
    private fun messageIn(payload: String): String {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return ""
        json.optJSONArray("ErrorMessage")?.let { list ->
            if (list.length() > 0) {
                return (0 until list.length()).joinToString(" ") { list.getString(it) }
            }
        }
        val direct = json.optString("ErrorMessage")
        if (direct.isNotBlank()) return direct
        val details = json.optString("ErrorDetails")
        if (details.isNotBlank()) return details
        // Τα σφάλματα ανά αρχείο κρύβονται μέσα στα ParsedResults
        json.optJSONArray("ParsedResults")?.let { list ->
            for (i in 0 until list.length()) {
                val item = list.optJSONObject(i) ?: continue
                val error = item.optString("ErrorMessage")
                if (error.isNotBlank()) return error
            }
        }
        return ""
    }

    companion object {
        private const val ENDPOINT = "https://api.ocr.space/parse/image"

        /** Το όριο του δωρεάν λογαριασμού. */
        const val MAX_BYTES = 1024 * 1024

        private const val RETRIES = 3

        /**
         * Μια λευκή εικόνα 1×1 σε PNG. Χρησιμεύει μόνο για τον έλεγχο του
         * κλειδιού: αν γυρίσει χωρίς σφάλμα, η σύνδεση και το κλειδί παίζουν.
         */
        private val PROBE_PNG: ByteArray = android.util.Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8" +
                "z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==",
            android.util.Base64.DEFAULT,
        )
    }
}
