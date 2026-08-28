package gr.prosfora.app.google

import gr.prosfora.app.debug.DebugLog
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

/** Minimal Google Drive REST v3 client. */
class DriveClient(private val accessToken: String) {

    data class DriveFile(
        val id: String,
        val name: String,
        val modifiedTime: String?,
        val modifiedBy: String = "",
        val modifiedByEmail: String = "",
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun builder(url: String) = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $accessToken")

    suspend fun findOrCreateFolder(name: String, parentId: String? = null): String = withContext(Dispatchers.IO) {
        val parentClause = if (parentId != null) " and '$parentId' in parents" else ""
        val query = "mimeType='$FOLDER_MIME' and name='${name.escapeQuery()}' and trashed=false$parentClause"
        list(query).firstOrNull()?.id ?: createFolder(name, parentId)
    }

    private suspend fun createFolder(name: String, parentId: String?): String = withContext(Dispatchers.IO) {
        val metadata = JSONObject().put("name", name).put("mimeType", FOLDER_MIME).apply {
            if (parentId != null) put("parents", JSONArray().put(parentId))
        }
        val request = builder("$API/files?fields=id")
            .post(metadata.toString().toRequestBody(JSON))
            .build()
        execute(request) { JSONObject(it).getString("id") }
    }

    suspend fun list(query: String): List<DriveFile> = withContext(Dispatchers.IO) {
        val result = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = buildString {
                append("$API/files?q=${query.urlEncode()}")
                append("&fields=nextPageToken,files(id,name,modifiedTime,lastModifyingUser(displayName,emailAddress))")
                append("&orderBy=modifiedTime desc&pageSize=100")
                pageToken?.let { append("&pageToken=${it.urlEncode()}") }
            }
            DebugLog.log(TAG, "Drive list query: $query")
            val page = execute(builder(url).get().build()) { JSONObject(it) }
            val files = page.optJSONArray("files") ?: JSONArray()
            for (i in 0 until files.length()) {
                val item = files.getJSONObject(i)
                val author = item.optJSONObject("lastModifyingUser")
                result += DriveFile(
                    id = item.getString("id"),
                    name = item.optString("name"),
                    modifiedTime = item.optString("modifiedTime").ifBlank { null },
                    modifiedBy = author?.optString("displayName").orEmpty(),
                    modifiedByEmail = author?.optString("emailAddress").orEmpty(),
                )
            }
            pageToken = page.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)
        DebugLog.log(TAG, "Drive list: ${result.size} αρχεία")
        result
    }

    suspend fun findInFolder(name: String, folderId: String): DriveFile? =
        list("name='${name.escapeQuery()}' and '$folderId' in parents and trashed=false").firstOrNull()

    suspend fun upload(
        name: String,
        bytes: ByteArray,
        mimeType: String,
        parentId: String? = null,
        convertToGoogleDoc: Boolean = false,
        ocrLanguage: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val metadata = JSONObject().put("name", name).apply {
            if (parentId != null) put("parents", JSONArray().put(parentId))
            if (convertToGoogleDoc) put("mimeType", DOC_MIME)
        }
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toString().toRequestBody(JSON))
            .addPart(bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        val url = buildString {
            append("$UPLOAD/files?uploadType=multipart&fields=id")
            if (ocrLanguage != null) append("&ocrLanguage=${ocrLanguage.urlEncode()}")
        }
        execute(builder(url).post(body).build()) { JSONObject(it).getString("id") }
    }

    /**
     * OCR μέσω προσωρινού Google Doc. Το προσωρινό αρχείο διαγράφεται πάντα.
     * Αν το οριστικό DELETE αποτύχει, μεταφέρεται άμεσα στον κάδο ώστε να μη
     * μένει ορατό στον φάκελο/Drive του χρήστη.
     */
    suspend fun readTextOf(bytes: ByteArray, name: String, mimeType: String, ocrLanguage: String = "el"): String {
        DebugLog.log(TAG, "OCR upload «$name» ($mimeType, ${bytes.size} bytes)")
        val docId = upload("prosfora-ocr-$name", bytes, mimeType, convertToGoogleDoc = true, ocrLanguage = ocrLanguage)
        return try {
            export(docId, TEXT_MIME).toString(Charsets.UTF_8).also {
                DebugLog.log(TAG, "OCR export $docId: ${it.length} χαρακτήρες")
            }
        } finally {
            cleanupTemporaryFile(docId, "OCR upload $name")
        }
    }

    suspend fun copyAsGoogleDoc(fileId: String, ocrLanguage: String = "el"): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("name", "prosfora-ocr-$fileId").put("mimeType", DOC_MIME)
        val url = "$API/files/$fileId/copy?ocrLanguage=${ocrLanguage.urlEncode()}&fields=id"
        execute(builder(url).post(payload.toString().toRequestBody(JSON)).build()) { JSONObject(it).getString("id") }
    }

    suspend fun readTextOf(fileId: String, ocrLanguage: String = "el"): String {
        DebugLog.log(TAG, "OCR copy $fileId")
        val copyId = copyAsGoogleDoc(fileId, ocrLanguage)
        return try {
            export(copyId, TEXT_MIME).toString(Charsets.UTF_8).also {
                DebugLog.log(TAG, "OCR export $copyId: ${it.length} χαρακτήρες")
            }
        } finally {
            cleanupTemporaryFile(copyId, "OCR copy $fileId")
        }
    }

    private suspend fun cleanupTemporaryFile(fileId: String, label: String) {
        val deleted = runCatching {
            delete(fileId)
            true
        }.onFailure {
            DebugLog.log(TAG, "DELETE προσωρινού $fileId απέτυχε: ${it.stackTraceToString().take(900)}")
        }.getOrDefault(false)
        if (deleted) {
            DebugLog.log(TAG, "DELETE προσωρινού $fileId OK · $label")
            return
        }
        val trashed = runCatching {
            trash(fileId)
            true
        }.onFailure {
            DebugLog.log(TAG, "TRASH προσωρινού $fileId απέτυχε: ${it.stackTraceToString().take(900)}")
        }.getOrDefault(false)
        DebugLog.log(TAG, "cleanup προσωρινού $fileId: ${if (trashed) "TRASH OK" else "ΤΕΛΙΚΗ ΑΠΟΤΥΧΙΑ"} · $label")
    }

    suspend fun export(fileId: String, mimeType: String): ByteArray = withContext(Dispatchers.IO) {
        val request = builder("$API/files/$fileId/export?mimeType=${mimeType.urlEncode()}").get().build()
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

    suspend fun moveToFolder(fileId: String, folderId: String) = withContext(Dispatchers.IO) {
        val current = execute(builder("$API/files/$fileId?fields=parents&supportsAllDrives=true").get().build()) { body ->
            val parents = JSONObject(body).optJSONArray("parents") ?: JSONArray()
            (0 until parents.length()).joinToString(",") { parents.getString(it) }
        }
        val url = buildString {
            append("$API/files/$fileId?addParents=$folderId&fields=id&supportsAllDrives=true")
            if (current.isNotBlank()) append("&removeParents=${current.urlEncode()}")
        }
        execute(builder(url).patch("{}".toRequestBody(JSON)).build()) { }
    }

    data class Collaborator(val permissionId: String, val email: String, val role: String, val isOwner: Boolean)

    suspend fun share(fileId: String, email: String, role: String = ROLE_WRITER, notify: Boolean = true) = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("type", "user").put("role", role).put("emailAddress", email)
        execute(builder("$API/files/$fileId/permissions?sendNotificationEmail=$notify&fields=id&supportsAllDrives=true")
            .post(payload.toString().toRequestBody(JSON)).build()) { }
    }

    suspend fun collaborators(fileId: String): List<Collaborator> = withContext(Dispatchers.IO) {
        val body = execute(builder("$API/files/$fileId/permissions?fields=permissions(id,emailAddress,role,type)&supportsAllDrives=true").get().build()) { it }
        val items = JSONObject(body).optJSONArray("permissions") ?: JSONArray()
        (0 until items.length()).mapNotNull { i ->
            val item = items.getJSONObject(i)
            val email = item.optString("emailAddress")
            if (item.optString("type") != "user" || email.isBlank()) null
            else Collaborator(item.getString("id"), email, item.optString("role"), item.optString("role") == "owner")
        }
    }

    suspend fun revoke(fileId: String, permissionId: String) = withContext(Dispatchers.IO) {
        http.newCall(builder("$API/files/$fileId/permissions/$permissionId?supportsAllDrives=true").delete().build()).execute().use { response ->
            if (!response.isSuccessful) throw driveError(response.code, response.body?.string())
        }
    }

    suspend fun delete(fileId: String) = withContext(Dispatchers.IO) {
        http.newCall(builder("$API/files/$fileId?supportsAllDrives=true").delete().build()).execute().use { response ->
            if (!response.isSuccessful) throw driveError(response.code, response.body?.string())
        }
    }

    private suspend fun trash(fileId: String) = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("trashed", true)
        execute(builder("$API/files/$fileId?supportsAllDrives=true&fields=id,trashed")
            .patch(payload.toString().toRequestBody(JSON)).build()) { }
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

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")
    private fun String.escapeQuery(): String = replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        private const val TAG = "drive"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val DOC_MIME = "application/vnd.google-apps.document"
        private const val TEXT_MIME = "text/plain"
        const val ROLE_READER = "reader"
        const val ROLE_WRITER = "writer"
    }
}
