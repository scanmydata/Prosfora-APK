package gr.prosfora.app.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Ελάχιστος client για το Drive REST API v3, πάνω σε OkHttp.
 *
 * Δεν χρησιμοποιούμε το google-api-client: για πέντε endpoints θα έφερνε
 * δεκάδες MB εξαρτήσεων και παλιό HTTP stack.
 *
 * Όλα τα αρχεία που αγγίζει τα έχει δημιουργήσει το ίδιο το app, όπως απαιτεί
 * το scope `drive.file`.
 */
class DriveClient(private val accessToken: String) {

    data class DriveFile(val id: String, val name: String, val modifiedTime: String?)

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun builder(url: String) = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $accessToken")

    suspend fun findOrCreateFolder(name: String, parentId: String? = null): String =
        withContext(Dispatchers.IO) {
            val parentClause = if (parentId != null) " and '$parentId' in parents" else ""
            val query = "mimeType='$FOLDER_MIME' and name='${name.escapeQuery()}' " +
                "and trashed=false$parentClause"
            list(query).firstOrNull()?.id ?: createFolder(name, parentId)
        }

    private suspend fun createFolder(name: String, parentId: String?): String =
        withContext(Dispatchers.IO) {
            val metadata = JSONObject()
                .put("name", name)
                .put("mimeType", FOLDER_MIME)
                .apply { if (parentId != null) put("parents", JSONArray().put(parentId)) }

            val request = builder("$API/files?fields=id")
                .post(metadata.toString().toRequestBody(JSON))
                .build()
            execute(request) { JSONObject(it).getString("id") }
        }

    suspend fun list(query: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val url = "$API/files?q=${query.urlEncode()}" +
            "&fields=files(id,name,modifiedTime)&orderBy=modifiedTime desc&pageSize=100"
        execute(builder(url).get().build()) { body ->
            val files = JSONObject(body).optJSONArray("files") ?: JSONArray()
            (0 until files.length()).map { i ->
                val item = files.getJSONObject(i)
                DriveFile(
                    id = item.getString("id"),
                    name = item.optString("name"),
                    modifiedTime = item.optString("modifiedTime").ifBlank { null },
                )
            }
        }
    }

    suspend fun findInFolder(name: String, folderId: String): DriveFile? =
        list("name='${name.escapeQuery()}' and '$folderId' in parents and trashed=false")
            .firstOrNull()

    /**
     * Ανεβάζει αρχείο. Με [convertToGoogleDoc] το Drive μετατρέπει το .docx σε
     * Google Doc — έτσι γίνεται μετά export σε PDF χωρίς δικό μας renderer.
     */
    suspend fun upload(
        name: String,
        bytes: ByteArray,
        mimeType: String,
        parentId: String? = null,
        convertToGoogleDoc: Boolean = false,
    ): String = withContext(Dispatchers.IO) {
        val metadata = JSONObject()
            .put("name", name)
            .apply {
                if (parentId != null) put("parents", JSONArray().put(parentId))
                if (convertToGoogleDoc) put("mimeType", DOC_MIME)
            }

        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody(JSON))
            .addPart(bytes.toRequestBody(mimeType.toMediaType()))
            .build()

        val request = builder("$UPLOAD/files?uploadType=multipart&fields=id")
            .post(body)
            .build()
        execute(request) { JSONObject(it).getString("id") }
    }

    /** Export ενός Google Doc στη μορφή [mimeType] (PDF ή .docx). */
    suspend fun export(fileId: String, mimeType: String): ByteArray = withContext(Dispatchers.IO) {
        val request = builder("$API/files/$fileId/export?mimeType=${mimeType.urlEncode()}")
            .get()
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw driveError(response.code, response.body?.string())
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    suspend fun download(fileId: String): ByteArray = withContext(Dispatchers.IO) {
        http.newCall(builder("$API/files/$fileId?alt=media").get().build()).execute().use { response ->
            if (!response.isSuccessful) throw driveError(response.code, response.body?.string())
            response.body?.bytes() ?: ByteArray(0)
        }
    }

    suspend fun delete(fileId: String) = withContext(Dispatchers.IO) {
        http.newCall(builder("$API/files/$fileId").delete().build()).execute().use { response ->
            if (!response.isSuccessful) throw driveError(response.code, response.body?.string())
        }
    }

    private inline fun <T> execute(request: Request, parse: (String) -> T): T =
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw driveError(response.code, body)
            parse(body)
        }

    private fun driveError(code: Int, body: String?): Exception {
        val detail = runCatching {
            JSONObject(body.orEmpty()).getJSONObject("error").getString("message")
        }.getOrNull()
        return IllegalStateException("Drive API $code: ${detail ?: body?.take(200) ?: "άγνωστο σφάλμα"}")
    }

    private fun String.urlEncode() = URLEncoder.encode(this, "UTF-8")

    /** Τα ονόματα με απόστροφο σπάνε το query language του Drive. */
    private fun String.escapeQuery() = replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val DOC_MIME = "application/vnd.google-apps.document"
        const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val PDF_MIME = "application/pdf"
    }
}
