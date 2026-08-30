package gr.prosfora.app.google

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Ελάχιστος client για το Google Sheets API v4.
 *
 * Το κοινόχρηστο Sheet παίζει τον ρόλο που είχε και στο AppSheet: είναι η βάση
 * που βλέπουν όλοι. Ο ιδιοκτήτης το μοιράζεται με τους συνεργάτες του μέσα από
 * το Google Drive, με τα κανονικά δικαιώματα της Google.
 */
class SheetsClient(private val accessToken: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun builder(url: String) = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer $accessToken")

    /** Τα ονόματα των tabs — για να ξέρουμε ποια λείπουν. */
    suspend fun sheetTitles(spreadsheetId: String): List<String> = withContext(Dispatchers.IO) {
        val url = "$API/$spreadsheetId?fields=sheets(properties(title))"
        execute(builder(url).get().build()) { body ->
            val sheets = JSONObject(body).optJSONArray("sheets") ?: JSONArray()
            (0 until sheets.length()).map {
                sheets.getJSONObject(it).getJSONObject("properties").getString("title")
            }
        }
    }

    /** Επιστρέφει το numeric sheetId που χρειάζεται το batchUpdate deleteDimension. */
    suspend fun sheetId(spreadsheetId: String, tab: String): Int = withContext(Dispatchers.IO) {
        val url = "$API/$spreadsheetId?fields=sheets(properties(sheetId,title))"
        execute(builder(url).get().build()) { body ->
            val sheets = JSONObject(body).optJSONArray("sheets") ?: JSONArray()
            for (i in 0 until sheets.length()) {
                val properties = sheets.getJSONObject(i).getJSONObject("properties")
                if (properties.getString("title") == tab) return@execute properties.getInt("sheetId")
            }
            error("Δεν βρέθηκε το tab $tab")
        }
    }

    suspend fun createSpreadsheet(title: String, tabs: List<String>): String =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("properties", JSONObject().put("title", title))
                .put(
                    "sheets",
                    JSONArray().apply {
                        tabs.forEach { tab ->
                            put(JSONObject().put("properties", JSONObject().put("title", tab)))
                        }
                    },
                )
            val request = builder("$API?fields=spreadsheetId")
                .post(payload.toString().toRequestBody(JSON))
                .build()
            execute(request) { JSONObject(it).getString("spreadsheetId") }
        }

    suspend fun addSheet(spreadsheetId: String, title: String) = withContext(Dispatchers.IO) {
        val payload = JSONObject().put(
            "requests",
            JSONArray().put(
                JSONObject().put(
                    "addSheet",
                    JSONObject().put("properties", JSONObject().put("title", title)),
                ),
            ),
        )
        val request = builder("$API/$spreadsheetId:batchUpdate")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        execute(request) { }
    }

    /** Όλες οι γραμμές ενός tab. */
    suspend fun readRows(spreadsheetId: String, tab: String): List<List<String>> =
        withContext(Dispatchers.IO) {
            val range = "'${tab.replace("'", "''")}'".urlEncode()
            val url = "$API/$spreadsheetId/values/$range?majorDimension=ROWS"
            execute(builder(url).get().build()) { body ->
                val values = JSONObject(body).optJSONArray("values") ?: JSONArray()
                (0 until values.length()).map { r ->
                    val row = values.getJSONArray(r)
                    (0 until row.length()).map { c -> row.optString(c) }
                }
            }
        }

    /**
     * Αντικαθιστά όλο το tab. Παραμένει για tabs όπου θέλουμε deterministic
     * full rebuild (π.χ. προσφορές/χώροι/σημειώσεις/οφειλές).
     */
    suspend fun replaceRows(spreadsheetId: String, tab: String, rows: List<List<String>>) =
        withContext(Dispatchers.IO) {
            val quoted = "'${tab.replace("'", "''")}'"
            execute(
                builder("$API/$spreadsheetId/values/${quoted.urlEncode()}:clear")
                    .post("{}".toRequestBody(JSON))
                    .build(),
            ) { }

            rows.chunked(WRITE_CHUNK).forEachIndexed { index, slice ->
                val firstRow = index * WRITE_CHUNK + 1
                val range = "$quoted!A$firstRow".urlEncode()
                val values = JSONArray().apply {
                    slice.forEach { row -> put(JSONArray().apply { row.forEach { put(it) } }) }
                }
                val payload = JSONObject().put("values", values)
                val request = builder(
                    "$API/$spreadsheetId/values/$range?valueInputOption=RAW",
                ).put(payload.toString().toRequestBody(JSON)).build()
                execute(request) { }
            }
        }

    /**
     * Πραγματικό CRUD πάνω σε tab με stable key στην πρώτη στήλη.
     *
     * - UPDATE: ενημερώνει μόνο την υπάρχουσα γραμμή.
     * - CREATE: κάνει append μόνο για νέα keys.
     * - DELETE: διαγράφει μόνο γραμμές των keys που δεν υπάρχουν πλέον στο desired.
     *
     * Δεν κάνει clear όλου του tab, ώστε ένα edit ενός εργαζομένου να μην αφήνει
     * το κοινόχρηστο Sheet προσωρινά ή μόνιμα με μόνο μία γραμμή.
     */
    suspend fun syncRowsCrud(
        spreadsheetId: String,
        tab: String,
        desiredRows: List<List<String>>,
    ) = withContext(Dispatchers.IO) {
        require(desiredRows.isNotEmpty()) { "Το CRUD sync χρειάζεται τουλάχιστον header row." }
        val existing = readRows(spreadsheetId, tab)
        val header = desiredRows.first()
        val desired = desiredRows.drop(1).filter { it.firstOrNull()?.isNotBlank() == true }
        val desiredByKey = LinkedHashMap<String, List<String>>()
        desired.forEach { row -> desiredByKey[row.first()] = row }

        val existingByKey = LinkedHashMap<String, Pair<Int, List<String>>>()
        existing.drop(1).forEachIndexed { index, row ->
            val key = row.firstOrNull()?.trim().orEmpty()
            if (key.isNotBlank()) existingByKey[key] = (index + 2) to row
        }

        // Guarantee the expected header without clearing data rows.
        if (existing.isEmpty()) {
            writeRowsAt(spreadsheetId, tab, 1, listOf(header))
        } else if (existing.first() != header) {
            writeRowsAt(spreadsheetId, tab, 1, listOf(header))
        }

        desiredByKey.forEach { (key, row) ->
            val current = existingByKey[key]
            if (current == null) {
                appendRows(spreadsheetId, tab, listOf(row))
            } else if (current.second != row) {
                writeRowsAt(spreadsheetId, tab, current.first, listOf(row))
            }
        }

        val stale = existingByKey.keys.filter { it !in desiredByKey.keys }
        if (stale.isNotEmpty()) {
            val sheet = sheetId(spreadsheetId, tab)
            val ranges = stale.mapNotNull { existingByKey[it]?.first }
                .sortedDescending()
                .map { rowNumber ->
                    JSONObject().put(
                        "deleteDimension",
                        JSONObject().put(
                            "range",
                            JSONObject()
                                .put("sheetId", sheet)
                                .put("dimension", "ROWS")
                                .put("startIndex", rowNumber - 1)
                                .put("endIndex", rowNumber),
                        ),
                    )
                }
            val payload = JSONObject().put("requests", JSONArray().apply { ranges.forEach(::put) })
            execute(
                builder("$API/$spreadsheetId:batchUpdate")
                    .post(payload.toString().toRequestBody(JSON))
                    .build(),
            ) { }
        }
    }

    private suspend fun writeRowsAt(
        spreadsheetId: String,
        tab: String,
        firstRow: Int,
        rows: List<List<String>>,
    ) = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext
        val quoted = "'${tab.replace("'", "''")}'"
        val range = "$quoted!A$firstRow".urlEncode()
        val values = JSONArray().apply {
            rows.forEach { row -> put(JSONArray().apply { row.forEach { put(it) } }) }
        }
        val request = builder(
            "$API/$spreadsheetId/values/$range?valueInputOption=RAW",
        ).put(JSONObject().put("values", values).toString().toRequestBody(JSON)).build()
        execute(request) { }
    }

    private suspend fun appendRows(
        spreadsheetId: String,
        tab: String,
        rows: List<List<String>>,
    ) = withContext(Dispatchers.IO) {
        if (rows.isEmpty()) return@withContext
        val quoted = "'${tab.replace("'", "''")}'"
        val values = JSONArray().apply {
            rows.forEach { row -> put(JSONArray().apply { row.forEach { put(it) } }) }
        }
        val request = builder(
            "$API/$spreadsheetId/values/$quoted:append?valueInputOption=RAW&insertDataOption=INSERT_ROWS",
        ).post(JSONObject().put("values", values).toString().toRequestBody(JSON)).build()
        execute(request) { }
    }

    private inline fun <T> execute(request: Request, parse: (String) -> T): T =
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(body).getJSONObject("error").getString("message")
                }.getOrNull()
                throw IllegalStateException("Sheets API ${response.code}: ${detail ?: body.take(200)}")
            }
            parse(body)
        }

    private fun String.urlEncode() = URLEncoder.encode(this, "UTF-8")

    companion object {
        private const val API = "https://sheets.googleapis.com/v4/spreadsheets"
        private const val WRITE_CHUNK = 2000
        private val JSON = "application/json; charset=utf-8".toMediaType()

        fun extractId(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            Regex("/spreadsheets/d/([a-zA-Z0-9-_]+)").find(trimmed)?.let { return it.groupValues[1] }
            return if (trimmed.matches(Regex("[a-zA-Z0-9-_]{20,}"))) trimmed else null
        }
    }
}
