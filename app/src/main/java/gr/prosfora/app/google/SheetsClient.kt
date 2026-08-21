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

    suspend fun createSpreadsheet(title: String, tabs: List<String>): String =
        withContext(Dispatchers.IO) {
            val payload = JSONObject()
                .put("properties", JSONObject().put("title", title))
                .put(
                    "sheets",
                    JSONArray().apply {
                        tabs.forEach { tab ->
                            put(
                                JSONObject().put(
                                    "properties",
                                    JSONObject().put("title", tab),
                                ),
                            )
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

    /** Όλες οι γραμμές ενός tab. Οι κενές γραμμές παραλείπονται από το API. */
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
     * Αντικαθιστά όλο το περιεχόμενο ενός tab. Πρώτα καθαρίζει, ώστε να μη
     * μένουν παλιές γραμμές όταν το νέο σύνολο είναι μικρότερο.
     */
    suspend fun replaceRows(spreadsheetId: String, tab: String, rows: List<List<String>>) =
        withContext(Dispatchers.IO) {
            val range = "'${tab.replace("'", "''")}'".urlEncode()
            execute(
                builder("$API/$spreadsheetId/values/$range:clear")
                    .post("{}".toRequestBody(JSON))
                    .build(),
            ) { }

            val values = JSONArray().apply {
                rows.forEach { row -> put(JSONArray().apply { row.forEach { put(it) } }) }
            }
            val payload = JSONObject().put("values", values)
            val request = builder(
                "$API/$spreadsheetId/values/$range?valueInputOption=RAW",
            ).put(payload.toString().toRequestBody(JSON)).build()
            execute(request) { }
        }

    private inline fun <T> execute(request: Request, parse: (String) -> T): T =
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val detail = runCatching {
                    JSONObject(body).getJSONObject("error").getString("message")
                }.getOrNull()
                throw IllegalStateException(
                    "Sheets API ${response.code}: ${detail ?: body.take(200)}",
                )
            }
            parse(body)
        }

    private fun String.urlEncode() = URLEncoder.encode(this, "UTF-8")

    companion object {
        private const val API = "https://sheets.googleapis.com/v4/spreadsheets"
        private val JSON = "application/json; charset=utf-8".toMediaType()

        /** Από ένα URL του Sheets βγάζει το αναγνωριστικό· δέχεται και σκέτο id. */
        fun extractId(input: String): String? {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return null
            Regex("/spreadsheets/d/([a-zA-Z0-9-_]+)").find(trimmed)?.let {
                return it.groupValues[1]
            }
            return if (trimmed.matches(Regex("[a-zA-Z0-9-_]{20,}"))) trimmed else null
        }
    }
}
