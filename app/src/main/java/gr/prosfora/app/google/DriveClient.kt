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

/**
 * Ελάχιστος client για το Drive REST API v3, πάνω σε OkHttp.
 *
 * Δεν χρησιμοποιούμε το google-api-client: για πέντε endpoints θα έφερνε
 * δεκάδες MB εξαρτήσεων και παλιό HTTP stack.
 *
 * Χρησιμοποιεί:
 *  - drive.file για αρχεία που δημιουργεί/διαχειρίζεται η εφαρμογή
 *  - drive.readonly για ανάγνωση αρχείων που ο χρήστης έχει ανεβάσει
 *    χειροκίνητα στους φακέλους του Drive
 */
class DriveClient(private val accessToken: String) {

    data class DriveFile(
        val id: String,
        val name: String,
        val modifiedTime: String?,
        /** Ποιος το άγγιξε τελευταίος — έχει νόημα σε κοινόχρηστο φάκελο. */
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

    suspend fun findOrCreateFolder(
    name: String,
    parentId: String? = null,
): String = withContext(Dispatchers.IO) {

    val parentClause =
        if (parentId != null) {
            " and '$parentId' in parents"
        } else {
            ""
        }

    val query =
        "mimeType='$FOLDER_MIME' and name='${name.escapeQuery()}' " +
            "and trashed=false$parentClause"

    list(query).firstOrNull()?.id
        ?: createFolder(name, parentId)
}

    private suspend fun createFolder(
        name: String,
        parentId: String?,
    ): String = withContext(Dispatchers.IO) {

        val metadata = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME)
            .apply {
                if (parentId != null) {
                    put(
                        "parents",
                        JSONArray().put(parentId),
                    )
                }
            }

        val request = builder("$API/files?fields=id")
            .post(
                metadata
                    .toString()
                    .toRequestBody(JSON),
            )
            .build()

        execute(request) {
            JSONObject(it).getString("id")
        }
    }

    /**
     * Επιστρέφει όλα τα αρχεία που ταιριάζουν στο query.
     *
     * Χρησιμοποιεί pagination ώστε να μη σταματά στα πρώτα 100 αρχεία.
     */
    suspend fun list(query: String): List<DriveFile> =
        withContext(Dispatchers.IO) {

            val result = mutableListOf<DriveFile>()
            var pageToken: String? = null

            do {
                val url = buildString {
                    append("$API/files")
                    append("?q=${query.urlEncode()}")

                    append(
                        "&fields=" +
                            "nextPageToken," +
                            "files(" +
                            "id," +
                            "name," +
                            "modifiedTime," +
                            "lastModifyingUser(displayName,emailAddress)" +
                            ")",
                    )

                    append("&orderBy=modifiedTime desc")
                    append("&pageSize=100")

                    pageToken?.let {
                        append("&pageToken=${it.urlEncode()}")
                    }
                }

                DebugLog.log(
                    TAG,
                    "Drive list query: $query" +
                        if (pageToken != null) {
                            " · συνέχεια σελίδας"
                        } else {
                            "",
                        },
                )

                val page = execute(
                    builder(url)
                        .get()
                        .build(),
                ) { body ->
                    JSONObject(body)
                }

                val files =
                    page.optJSONArray("files")
                        ?: JSONArray()

                for (i in 0 until files.length()) {
                    val item = files.getJSONObject(i)
                    val author =
                        item.optJSONObject("lastModifyingUser")

                    result += DriveFile(
                        id = item.getString("id"),
                        name = item.optString("name"),
                        modifiedTime = item
                            .optString("modifiedTime")
                            .ifBlank { null },
                        modifiedBy = author
                            ?.optString("displayName")
                            .orEmpty(),
                        modifiedByEmail = author
                            ?.optString("emailAddress")
                            .orEmpty(),
                    )
                }

                pageToken = page
                    .optString("nextPageToken")
                    .takeIf { it.isNotBlank() }

            } while (pageToken != null)

            DebugLog.log(
                TAG,
                "Drive list: ${result.size} αρχεία",
            )

            result
        }

    suspend fun findInFolder(
        name: String,
        folderId: String,
    ): DriveFile? =
        list(
            "name='${name.escapeQuery()}' " +
                "and '$folderId' in parents " +
                "and trashed=false",
        ).firstOrNull()

    /**
     * Ανεβάζει αρχείο.
     *
     * Με [convertToGoogleDoc] το Drive μετατρέπει το αρχείο
     * σε Google Doc — έτσι γίνεται μετά export σε PDF ή text.
     */
    suspend fun upload(
        name: String,
        bytes: ByteArray,
        mimeType: String,
        parentId: String? = null,
        convertToGoogleDoc: Boolean = false,
        ocrLanguage: String? = null,
    ): String = withContext(Dispatchers.IO) {

        val metadata = JSONObject()
            .put("name", name)
            .apply {
                if (parentId != null) {
                    put(
                        "parents",
                        JSONArray().put(parentId),
                    )
                }

                if (convertToGoogleDoc) {
                    put("mimeType", DOC_MIME)
                }
            }

        val body = MultipartBody.Builder()
            .setType(
                "multipart/related".toMediaType(),
            )
            .addPart(
                metadata
                    .toString()
                    .toRequestBody(JSON),
            )
            .addPart(
                bytes.toRequestBody(
                    mimeType.toMediaType(),
                ),
            )
            .build()

        val url = buildString {
            append(
                "$UPLOAD/files" +
                    "?uploadType=multipart" +
                    "&fields=id",
            )

            if (ocrLanguage != null) {
                append(
                    "&ocrLanguage=" +
                        ocrLanguage.urlEncode(),
                )
            }
        }

        execute(
            builder(url)
                .post(body)
                .build(),
        ) {
            JSONObject(it).getString("id")
        }
    }

    /**
     * OCR από το ίδιο το Drive, με ανέβασμα και όχι με αντιγραφή.
     *
     * Το προσωρινό Google Doc σβήνεται ό,τι κι αν γίνει.
     */
    suspend fun readTextOf(
        bytes: ByteArray,
        name: String,
        mimeType: String,
        ocrLanguage: String = "el",
    ): String {

        DebugLog.log(
            TAG,
            "ανέβασμα «$name» " +
                "($mimeType, ${bytes.size} bytes) " +
                "ως έγγραφο Google, " +
                "ocrLanguage=$ocrLanguage",
        )

        val docId = upload(
            name = "prosfora-ocr-$name",
            bytes = bytes,
            mimeType = mimeType,
            convertToGoogleDoc = true,
            ocrLanguage = ocrLanguage,
        )

        DebugLog.log(
            TAG,
            "προσωρινό έγγραφο $docId · εξαγωγή σε κείμενο",
        )

        return try {
            export(
                docId,
                TEXT_MIME,
            )
                .toString(Charsets.UTF_8)
                .also {
                    DebugLog.log(
                        TAG,
                        "εξήχθησαν ${it.length} χαρακτήρες",
                    )
                }
        } finally {
            val wiped =
                runCatching {
                    delete(docId)
                }.isSuccess

            DebugLog.log(
                TAG,
                "διαγραφή προσωρινού $docId: " +
                    if (wiped) "έγινε" else "ΑΠΕΤΥΧΕ",
            )
        }
    }

    /**
     * Αντιγράφει ένα αρχείο του Drive ως Google Doc.
     *
     * Έτσι διαβάζεται το κείμενο ενός PDF χωρίς να μπει
     * βιβλιοθήκη PDF στο apk.
     */
    suspend fun copyAsGoogleDoc(
        fileId: String,
        ocrLanguage: String = "el",
    ): String = withContext(Dispatchers.IO) {

        val payload = JSONObject()
            .put(
                "name",
                "prosfora-ocr-$fileId",
            )
            .put(
                "mimeType",
                DOC_MIME,
            )

        val url =
            "$API/files/$fileId/copy" +
                "?ocrLanguage=${ocrLanguage.urlEncode()}" +
                "&fields=id"

        execute(
            builder(url)
                .post(
                    payload
                        .toString()
                        .toRequestBody(JSON),
                )
                .build(),
        ) {
            JSONObject(it).getString("id")
        }
    }

    /**
     * Το κείμενο ενός PDF που βρίσκεται ήδη στο Drive.
     *
     * Το προσωρινό Google Doc σβήνεται ό,τι κι αν γίνει.
     */
    suspend fun readTextOf(
        fileId: String,
        ocrLanguage: String = "el",
    ): String {

        DebugLog.log(
            TAG,
            "αντιγραφή $fileId ως έγγραφο Google",
        )

        val copyId = copyAsGoogleDoc(
            fileId,
            ocrLanguage,
        )

        return try {
            export(
                copyId,
                TEXT_MIME,
            )
                .toString(Charsets.UTF_8)
                .also {
                    DebugLog.log(
                        TAG,
                        "εξήχθησαν ${it.length} χαρακτήρες",
                    )
                }
        } finally {
            val wiped =
                runCatching {
                    delete(copyId)
                }.isSuccess

            DebugLog.log(
                TAG,
                "διαγραφή προσωρινού $copyId: " +
                    if (wiped) "έγινε" else "ΑΠΕΤΥΧΕ",
            )
        }
    }

    /** Export ενός Google Doc στη μορφή [mimeType]. */
    suspend fun export(
        fileId: String,
        mimeType: String,
    ): ByteArray = withContext(Dispatchers.IO) {

        val request = builder(
            "$API/files/$fileId/export" +
                "?mimeType=${mimeType.urlEncode()}",
        )
            .get()
            .build()

        http.newCall(request)
            .execute()
            .use { response ->

                if (!response.isSuccessful) {
                    throw driveError(
                        response.code,
                        response.body?.string(),
                    )
                }

                response.body?.bytes()
                    ?: ByteArray(0)
            }
    }

    /**
     * Κατεβάζει το πραγματικό αρχείο.
     */
    suspend fun download(
        fileId: String,
    ): ByteArray = withContext(Dispatchers.IO) {

        http.newCall(
            builder(
                "$API/files/$fileId?alt=media",
            )
                .get()
                .build(),
        )
            .execute()
            .use { response ->

                if (!response.isSuccessful) {
                    throw driveError(
                        response.code,
                        response.body?.string(),
                    )
                }

                response.body?.bytes()
                    ?: ByteArray(0)
            }
    }

    /**
     * Μεταφέρει ένα αρχείο σε φάκελο.
     */
    suspend fun moveToFolder(
        fileId: String,
        folderId: String,
    ) = withContext(Dispatchers.IO) {

        val current = execute(
            builder(
                "$API/files/$fileId?fields=parents",
            )
                .get()
                .build(),
        ) { body ->

            val parents =
                JSONObject(body)
                    .optJSONArray("parents")
                    ?: JSONArray()

            (0 until parents.length())
                .joinToString(",") {
                    parents.getString(it)
                }
        }

        val url = buildString {

            append(
                "$API/files/$fileId" +
                    "?addParents=$folderId" +
                    "&fields=id",
            )

            if (current.isNotBlank()) {
                append(
                    "&removeParents=" +
                        current.urlEncode(),
                )
            }
        }

        execute(
            builder(url)
                .patch(
                    "{}".toRequestBody(JSON),
                )
                .build(),
        ) { }
    }

    data class Collaborator(
        val permissionId: String,
        val email: String,
        val role: String,
        val isOwner: Boolean,
    )

    /**
     * Δίνει πρόσβαση σε έναν συνεργάτη.
     */
    suspend fun share(
        fileId: String,
        email: String,
        role: String = ROLE_WRITER,
        notify: Boolean = true,
    ) = withContext(Dispatchers.IO) {

        val payload = JSONObject()
            .put("type", "user")
            .put("role", role)
            .put("emailAddress", email)

        val request = builder(
            "$API/files/$fileId/permissions" +
                "?sendNotificationEmail=$notify" +
                "&fields=id",
        )
            .post(
                payload
                    .toString()
                    .toRequestBody(JSON),
            )
            .build()

        execute(request) { }
    }

    suspend fun collaborators(
        fileId: String,
    ): List<Collaborator> =
        withContext(Dispatchers.IO) {

            val url =
                "$API/files/$fileId/permissions" +
                    "?fields=" +
                    "permissions(" +
                    "id,emailAddress,role,type" +
                    ")"

            execute(
                builder(url)
                    .get()
                    .build(),
            ) { body ->

                val items =
                    JSONObject(body)
                        .optJSONArray("permissions")
                        ?: JSONArray()

                (0 until items.length())
                    .mapNotNull { i ->

                        val item =
                            items.getJSONObject(i)

                        val email =
                            item
                                .optString("emailAddress")

                        if (
                            item.optString("type") != "user" ||
                            email.isBlank()
                        ) {
                            return@mapNotNull null
                        }

                        Collaborator(
                            permissionId =
                                item.getString("id"),
                            email = email,
                            role = item.optString("role"),
                            isOwner =
                                item.optString("role") == "owner",
                        )
                    }
            }
        }

    suspend fun revoke(
        fileId: String,
        permissionId: String,
    ) = withContext(Dispatchers.IO) {

        http.newCall(
            builder(
                "$API/files/$fileId" +
                    "/permissions/$permissionId",
            )
                .delete()
                .build(),
        )
            .execute()
            .use { response ->

                if (!response.isSuccessful) {
                    throw driveError(
                        response.code,
                        response.body?.string(),
                    )
                }
            }
    }

    suspend fun delete(
        fileId: String,
    ) = withContext(Dispatchers.IO) {

        http.newCall(
            builder("$API/files/$fileId")
                .delete()
                .build(),
        )
            .execute()
            .use { response ->

                if (!response.isSuccessful) {
                    throw driveError(
                        response.code,
                        response.body?.string(),
                    )
                }
            }
    }

    private inline fun <T> execute(
        request: Request,
        parse: (String) -> T,
    ): T =
        http.newCall(request)
            .execute()
            .use { response ->

                val body =
                    response.body
                        ?.string()
                        .orEmpty()

                if (!response.isSuccessful) {
                    throw driveError(
                        response.code,
                        body,
                    )
                }

                parse(body)
            }

    private fun driveError(
        code: Int,
        body: String?,
    ): Exception {

        val detail = runCatching {
            JSONObject(
                body.orEmpty(),
            )
                .getJSONObject("error")
                .getString("message")
        }.getOrNull()

        return IllegalStateException(
            "Drive API $code: " +
                (
                    detail
                        ?: body?.take(200)
                        ?: "άγνωστο σφάλμα"
                    ),
        )
    }

    private fun String.urlEncode(): String =
        URLEncoder.encode(
            this,
            "UTF-8",
        )

    /**
     * Τα ονόματα με απόστροφο σπάνε το query language του Drive.
     */
    private fun String.escapeQuery(): String =
        replace("\\", "\\\\")
            .replace("'", "\\'")

    companion object {

        private const val API =
            "https://www.googleapis.com/drive/v3"

        private const val UPLOAD =
            "https://www.googleapis.com/upload/drive/v3"

        private val JSON =
            "application/json; charset=utf-8"
                .toMediaType()

        const val FOLDER_MIME =
            "application/vnd.google-apps.folder"

        private const val TAG =
            "drive"

        const val DOC_MIME =
            "application/vnd.google-apps.document"

        const val DOCX_MIME =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

        const val PDF_MIME =
            "application/pdf"

        const val TEXT_MIME =
            "text/plain"

        const val ROLE_WRITER =
            "writer"

        const val ROLE_READER =
            "reader"
    }
}
