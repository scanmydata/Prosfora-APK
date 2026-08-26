package gr.prosfora.app.debt

import kotlinx.coroutines.Dispatchers
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
 * **Μηχανή 3** πρώτα: είναι αυτή που δίνει σωστό αποτέλεσμα στα ελληνικά έντυπα
 * και στα screenshot, και δεν παίρνει παράμετρο γλώσσας — αναγνωρίζει μόνη της.
 * Αν αρνηθεί, δοκιμάζεται η 1 με ρητά ελληνικά.
 */
class OcrSpaceClient(private val apiKey: String) {

    class Failure(message: String) : IllegalStateException(message)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(180, TimeUnit.SECONDS)
        .build()

    /**
     * Το κείμενο του αρχείου. Ρίχνει [Failure] με το μήνυμα του παρόχου, ώστε ο
     * καλών να αποφασίσει αν θα δοκιμάσει άλλον δρόμο.
     */
    suspend fun read(bytes: ByteArray, fileName: String): String = withContext(Dispatchers.IO) {
        require(apiKey.isNotBlank()) { "Δεν έχει οριστεί κλειδί OCR" }
        val kind = DocumentBytes.kindOf(bytes)
            ?: throw Failure("Δεν αναγνωρίστηκε η μορφή του αρχείου")
        if (bytes.size > MAX_BYTES) {
            throw Failure("Το αρχείο ξεπερνά το 1 MB που δέχεται το ocr.space")
        }

        // Πρώτα η 3, που δεν παίρνει γλώσσα· η 1 θέλει ρητά «gre»
        runCatching { post(bytes, fileName, kind, engine = 3, language = null) }
            .getOrElse { post(bytes, fileName, kind, engine = 1, language = "gre") }
    }

    private fun post(
        bytes: ByteArray,
        fileName: String,
        kind: DocumentBytes.Kind,
        engine: Int,
        language: String?,
    ): String {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("apikey", apiKey)
            .addFormDataPart("OCREngine", engine.toString())
            .addFormDataPart("filetype", kind.ocrType)
            .addFormDataPart("isOverlayRequired", "false")
            .addFormDataPart("detectOrientation", "true")
            .addFormDataPart("scale", "true")
            .apply { if (language != null) addFormDataPart("language", language) }
            .addFormDataPart("file", fileName, bytes.toRequestBody(kind.mime.toMediaType()))
            .build()

        val request = Request.Builder().url(ENDPOINT).post(body).build()
        http.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw Failure("ocr.space ${response.code}: ${payload.take(160)}")
            }
            return parse(payload)
        }
    }

    private fun parse(payload: String): String {
        val json = runCatching { JSONObject(payload) }
            .getOrElse { throw Failure("Ακατανόητη απάντηση από το ocr.space") }

        if (json.optBoolean("IsErroredOnProcessing")) {
            throw Failure(errorOf(json))
        }
        val results = json.optJSONArray("ParsedResults")
            ?: throw Failure(errorOf(json).ifBlank { "Το ocr.space δεν επέστρεψε κείμενο" })

        val text = (0 until results.length())
            .joinToString("\n") { results.getJSONObject(it).optString("ParsedText") }
            .trim()
        if (text.isBlank()) throw Failure("Το ocr.space δεν διάβασε κείμενο")
        return text
    }

    private fun errorOf(json: JSONObject): String {
        json.optJSONArray("ErrorMessage")?.let { list ->
            if (list.length() > 0) return (0 until list.length()).joinToString(" ") { list.getString(it) }
        }
        return json.optString("ErrorMessage").ifBlank { json.optString("ErrorDetails") }
    }

    companion object {
        private const val ENDPOINT = "https://api.ocr.space/parse/image"

        /** Το όριο του δωρεάν λογαριασμού. */
        const val MAX_BYTES = 1024 * 1024
    }
}
