package gr.prosfora.app.google

import gr.prosfora.app.debug.DebugLog
import gr.prosfora.app.util.reason
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
import java.time.Instant
import java.time.temporal.ChronoUnit
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

    private fun builder(url: String) = Request.Builder().url(url).header("Authorization", "Bearer $accessToken")

    suspend fun findOrCreateFolder(name: String, parentId: String? = null): String = withContext(Dispatchers.IO) {
        val parentClause = if (parentId != null) " and '$parentId' in parents" else ""
        val query = "mimeType='$FOLDER_MIME' and name='${name.escapeQuery()}' and trashed=false$parentClause"
        list(query).firstOrNull()?.id ?: createFolder(name, parentId)
    }

    private suspend fun createFolder(name: String, parentId: String?): String = withContext(Dispatchers.IO) {
        val metadata = JSONObject().put("name", name).put("mimeType", FOLDER_MIME).apply {
            if (parentId != null) put("parents", JSONArray().put(parentId))
        }
        execute(builder("$API/files?fields=id").post(metadata.toString().toRequestBody(JSON)).build()) { JSONObject(it).getString("id") }
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
        filesNamed(name, folderId).firstOrNull()

    /**
     * **Όλα** τα αρχεία με αυτό το όνομα μέσα στον φάκελο.
     *
     * Το Drive επιτρέπει διπλά ονόματα, οπότε το «βρες ένα» δεν αρκεί όταν
     * σκοπός είναι να μη μείνει κανένα παλιό αντίγραφο πίσω.
     */
    suspend fun filesNamed(name: String, folderId: String): List<DriveFile> =
        list("name='${name.escapeQuery()}' and '$folderId' in parents and trashed=false")

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
     * @param tempParentId πού γεννιέται το προσωρινό έγγραφο του OCR. Χωρίς
     * αυτό πέφτει στη **ρίζα** του Drive, και κάθε φορά που η διαγραφή του
     * αποτύχει —χαμένο δίκτυο, ληγμένο token— μένει εκεί για πάντα. Μέσα στον
     * φάκελο της εφαρμογής, το χειρότερο που μπορεί να συμβεί είναι ένα αρχείο
     * παραπάνω σε γνωστό σημείο, που το μαζεύει το [sweepOcrLeftovers].
     */
    suspend fun readTextOf(
        bytes: ByteArray,
        name: String,
        mimeType: String,
        ocrLanguage: String = "el",
        tempParentId: String? = null,
    ): String {
        DebugLog.log(TAG, "OCR upload «$name» ($mimeType, ${bytes.size} bytes)")
        val docId = upload(
            name = "$OCR_PREFIX$name",
            bytes = bytes,
            mimeType = mimeType,
            parentId = tempParentId,
            convertToGoogleDoc = true,
            ocrLanguage = ocrLanguage,
        )
        return try {
            export(docId, TEXT_MIME).toString(Charsets.UTF_8)
        } finally {
            cleanupTemporaryFile(docId, "OCR upload $name")
        }
    }

    suspend fun copyAsGoogleDoc(
        fileId: String,
        ocrLanguage: String = "el",
        tempParentId: String? = null,
    ): String = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("name", "$OCR_PREFIX$fileId").put("mimeType", DOC_MIME).apply {
            if (tempParentId != null) put("parents", JSONArray().put(tempParentId))
        }
        val url = "$API/files/$fileId/copy?ocrLanguage=${ocrLanguage.urlEncode()}&fields=id"
        execute(builder(url).post(payload.toString().toRequestBody(JSON)).build()) { JSONObject(it).getString("id") }
    }

    suspend fun readTextOf(fileId: String, ocrLanguage: String = "el", tempParentId: String? = null): String {
        DebugLog.log(TAG, "OCR copy $fileId")
        val copyId = copyAsGoogleDoc(fileId, ocrLanguage, tempParentId)
        return try {
            export(copyId, TEXT_MIME).toString(Charsets.UTF_8)
        } finally {
            cleanupTemporaryFile(copyId, "OCR copy $fileId")
        }
    }

    /**
     * Μαζεύει τα προσωρινά του OCR που έμειναν πίσω από παλιότερες σαρώσεις.
     *
     * Το Drive δεν δίνει «προσωρινό αρχείο»: ό,τι ανεβαίνει μένει μέχρι να
     * σβηστεί. Όταν η διαγραφή αποτύγχανε, το έγγραφο έμενε στη ρίζα του Drive
     * του χρήστη — και μαζευόταν. Το πρόθεμα `prosfora-ocr-` είναι δικό μας
     * και κανενός άλλου, οπότε ό,τι το φέρει και ανήκει σε εμάς είναι σκουπίδι.
     */
    suspend fun sweepOcrLeftovers(): Int {
        // Για το `name`, το `contains` του Drive ταιριάζει πρόθεμα — ακριβώς
        // αυτό που χρειάζεται εδώ
        val leftovers = runCatching {
            list("name contains '$OCR_PREFIX' and mimeType='$DOC_MIME' and 'me' in owners and trashed=false")
        }.onFailure { DebugLog.log(TAG, "αναζήτηση υπολειμμάτων OCR απέτυχε: ${it.reason()}") }
            .getOrDefault(emptyList())
            // Ένα έγγραφο που μόλις γεννήθηκε μπορεί να το διαβάζει αυτή τη
            // στιγμή κάποια άλλη σάρωση — στην ίδια συσκευή ή σε άλλη. Μετά από
            // λίγα λεπτά, κανένα OCR δεν το χρειάζεται πια.
            .filter { olderThan(it.modifiedTime, LEFTOVER_AGE_MINUTES) }
        if (leftovers.isEmpty()) return 0
        DebugLog.log(TAG, "υπολείμματα OCR προς καθάρισμα: ${leftovers.size}")
        leftovers.forEach { cleanupTemporaryFile(it.id, "υπόλειμμα OCR «${it.name}»") }
        return leftovers.size
    }

    /** Αγνώστου ηλικίας σημαίνει «παλιό»: αλλιώς δεν θα καθαριζόταν ποτέ. */
    private fun olderThan(modifiedTime: String?, minutes: Long): Boolean {
        val stamp = modifiedTime?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return true
        return stamp.isBefore(Instant.now().minus(minutes, ChronoUnit.MINUTES))
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
        val detail = runCatching { JSONObject(body.orEmpty()).getJSONObject("error").getString("message") }.getOrNull()
        return IllegalStateException("Drive API $code: ${detail ?: body?.take(200) ?: "άγνωστο σφάλμα"}")
    }

    private fun String.urlEncode(): String = URLEncoder.encode(this, "UTF-8")
    private fun String.escapeQuery(): String = replace("\\", "\\\\").replace("'", "\\'")

    companion object {
        private const val TAG = "drive"

        /** Το πρόθεμα των προσωρινών εγγράφων που φτιάχνει το OCR. */
        const val OCR_PREFIX = "prosfora-ocr-"

        /** Πόσο παλιό πρέπει να είναι ένα προσωρινό για να θεωρηθεί ξεχασμένο. */
        private const val LEFTOVER_AGE_MINUTES = 10L
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private val JSON = "application/json; charset=utf-8".toMediaType()
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val DOC_MIME = "application/vnd.google-apps.document"
        const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        const val PDF_MIME = "application/pdf"
        const val TEXT_MIME = "text/plain"
        const val ROLE_WRITER = "writer"
        const val ROLE_READER = "reader"
    }
}
