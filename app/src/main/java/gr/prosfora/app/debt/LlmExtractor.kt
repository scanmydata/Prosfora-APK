package gr.prosfora.app.debt

import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.DebtKind
import gr.prosfora.app.debug.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * Τελευταίο δίχτυ: όταν κανένας κανόνας δεν αναγνωρίζει το παραστατικό, το
 * κείμενο πάει σε ένα μοντέλο γλώσσας και ζητιέται δομημένη απάντηση.
 *
 * **Γιατί χρειάζεται.** Οι κανόνες αγκυρώνονται σε ετικέτες και σε αποστάσεις.
 * Η ίδια σελίδα όμως βγαίνει από το OCR άλλοτε με την τιμή δίπλα στην ετικέτα
 * της και άλλοτε σε στήλες — πρώτα όλες οι ετικέτες, μετά όλες οι τιμές. Κάθε
 * φορά που προσαρμόζεται ένας κανόνας σε μια διάταξη, εμφανίζεται η επόμενη.
 * Ένα μοντέλο δεν μετράει αποστάσεις: διαβάζει.
 *
 * **Γιατί μένει τελευταίο.** Είναι αργό, θέλει δίκτυο, στέλνει το παραστατικό
 * σε τρίτον, και μπορεί να πει ψέματα με σιγουριά. Οι κανόνες είναι
 * ντετερμινιστικοί και ελεγμένοι· τρέχουν πρώτοι και, όταν πιάνουν, το μοντέλο
 * δεν καλείται καν.
 *
 * **Μόνο δωρεάν μοντέλα.** Η λίστα ζητιέται από τον πάροχο και φιλτράρεται με
 * την τιμολόγησή του, ώστε να μη χρεωθεί ποτέ τίποτα κατά λάθος αν αλλάξουν τα
 * ονόματα των μοντέλων.
 *
 * Ό,τι βγάλει περνάει από την ίδια επιβεβαίωση του χρήστη με όλα τα υπόλοιπα.
 */
class LlmExtractor(
    private val groqKey: String,
    private val openRouterKey: String,
) {

    enum class Provider(val label: String, val chat: String, val models: String) {
        GROQ(
            "Groq",
            "https://api.groq.com/openai/v1/chat/completions",
            "https://api.groq.com/openai/v1/models",
        ),
        OPENROUTER(
            "OpenRouter",
            "https://openrouter.ai/api/v1/chat/completions",
            "https://openrouter.ai/api/v1/models",
        ),
    }

    private data class Candidate(val provider: Provider, val id: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    val available: Boolean get() = groqKey.isNotBlank() || openRouterKey.isNotBlank()

    /**
     * Οι οφειλές που διάβασε το μοντέλο. Κενή λίστα σημαίνει ότι κανένα
     * διαθέσιμο μοντέλο δεν έβγαλε χρησιμοποιήσιμη απάντηση.
     */
    suspend fun extract(
        text: String,
        fileName: String,
        driveFileId: String,
    ): List<DebtEntity> = withContext(Dispatchers.IO) {
        if (!available || text.isBlank()) return@withContext emptyList()

        val candidates = runCatching { candidates() }.getOrDefault(emptyList())
        if (candidates.isEmpty()) {
            DebugLog.log(TAG, "κανένα δωρεάν μοντέλο διαθέσιμο")
            return@withContext emptyList()
        }
        DebugLog.log(TAG, "υποψήφια: " + candidates.joinToString { "${it.provider.label}/${it.id}" })

        candidates.forEach { candidate ->
            val rows = runCatching { ask(candidate, text, fileName, driveFileId) }
                .onFailure { DebugLog.log(TAG, "${candidate.id}: ${it.message}") }
                .getOrDefault(emptyList())
            if (rows.isNotEmpty()) {
                DebugLog.log(TAG, "${candidate.provider.label}/${candidate.id}: ${rows.size} γραμμές")
                return@withContext rows
            }
        }
        emptyList()
    }

    // ------------------------------------------------------------ μοντέλα ---

    private fun candidates(): List<Candidate> = buildList {
        if (groqKey.isNotBlank()) {
            addAll(groqModels().take(MAX_PER_PROVIDER).map { Candidate(Provider.GROQ, it) })
        }
        if (openRouterKey.isNotBlank()) {
            // Περισσότερα από το Groq: τα δωρεάν του OpenRouter μοιράζονται
            // δεξαμενή με όλο τον κόσμο και γυρίζουν 429 χωρίς προειδοποίηση
            addAll(freeOpenRouter().take(MAX_FREE_POOL).map { Candidate(Provider.OPENROUTER, it) })
        }
    }

    /**
     * Τα μοντέλα κειμένου του Groq. Όλα όσα δίνει το κλειδί είναι δωρεάν στο
     * επίπεδο προγραμματιστή, οπότε φεύγουν μόνο όσα δεν κάνουν συνομιλία —
     * αναγνώριση ομιλίας, φωνή, φίλτρα ασφαλείας.
     */
    private fun groqModels(): List<String> {
        val ids = idsOf(Provider.GROQ.models, groqKey) { it.optString("id") }
        return ids
            .filterNot { id -> SKIP.any { id.contains(it, ignoreCase = true) } }
            .sortedByDescending { score(it) }
    }

    /**
     * Τα μοντέλα του OpenRouter που **κοστίζουν μηδέν**.
     *
     * Το φίλτρο γίνεται στην τιμολόγηση και όχι στο όνομα: το επίθεμα `:free`
     * είναι σύμβαση που μπορεί να αλλάξει, η τιμή όχι.
     */
    private fun freeOpenRouter(): List<String> {
        val ids = idsOf(Provider.OPENROUTER.models, openRouterKey) { model ->
            val price = model.optJSONObject("pricing")
            val prompt = price?.optString("prompt")?.toDoubleOrNull() ?: 1.0
            val completion = price?.optString("completion")?.toDoubleOrNull() ?: 1.0
            val context = model.optInt("context_length", 0)
            if (prompt == 0.0 && completion == 0.0 && context >= MIN_CONTEXT && writesText(model)) {
                model.optString("id")
            } else {
                ""
            }
        }.filterNot { id -> SKIP.any { id.contains(it, ignoreCase = true) } }

        // Ο δρομολογητής τους μπροστά: διαλέγει μόνος του ένα δωρεάν μοντέλο
        // που απαντάει εκείνη τη στιγμή, και τα δωρεάν είναι συχνά κορεσμένα
        val router = ids.filter { it == FREE_ROUTER }
        return router + (ids - router.toSet()).sortedByDescending { score(it) }
    }

    /**
     * Βγάζει το μοντέλο κείμενο, και **μόνο** κείμενο;
     *
     * Στη λίστα των δωρεάν κάθονται και γεννήτριες μουσικής, με μηδενική τιμή
     * και κανονικά ονόματα. Η μορφή εξόδου τις ξεχωρίζει χωρίς να χρειάζεται
     * να μαντέψουμε από το όνομα.
     */
    private fun writesText(model: JSONObject): Boolean {
        val out = model.optJSONObject("architecture")?.optJSONArray("output_modalities")
            ?: return true
        val kinds = (0 until out.length()).map { out.optString(it) }
        return kinds.contains("text") && kinds.none { it == "audio" || it == "image" }
    }

    private fun idsOf(url: String, key: String, pick: (JSONObject) -> String): List<String> {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key").get().build()
        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                DebugLog.log(TAG, "λίστα μοντέλων $url: HTTP ${response.code}")
                return emptyList()
            }
            val data = JSONObject(body).optJSONArray("data") ?: JSONArray()
            (0 until data.length())
                .map { pick(data.getJSONObject(it)) }
                .filter { it.isNotBlank() }
        }
    }

    /**
     * Ποιο μοντέλο αξίζει να δοκιμαστεί πρώτο.
     *
     * Μετράει η οικογένεια και το μέγεθος: τα μικρά μοντέλα βγάζουν πρόθυμα
     * JSON που δεν στέκει, και ένα λάθος ποσό είναι χειρότερο από καμία
     * απάντηση — αν και ο χρήστης επιβεβαιώνει πάντα πριν αποθηκευτεί.
     */
    private fun score(id: String): Int {
        var points = 0
        FAMILIES.forEachIndexed { at, family ->
            if (id.contains(family, ignoreCase = true)) points += (FAMILIES.size - at) * 10
        }
        if (id.contains("70b", true) || id.contains("72b", true)) points += 25
        if (id.contains("instruct", true) || id.contains("versatile", true)) points += 5
        return points
    }

    // ------------------------------------------------------------- ερώτημα ---

    private fun ask(
        candidate: Candidate,
        text: String,
        fileName: String,
        driveFileId: String,
    ): List<DebtEntity> {
        val payload = JSONObject()
            .put("model", candidate.id)
            .put("temperature", 0)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", SYSTEM))
                    .put(
                        JSONObject().put("role", "user")
                            .put("content", "Filename: $fileName\n\n${text.take(MAX_TEXT)}"),
                    ),
            )
            // Πολλά μικρά μοντέλα αγνοούν την οδηγία και απαντούν με πρόζα·
            // όπου υποστηρίζεται, αυτό το κλείνει στην πηγή
            .put("response_format", JSONObject().put("type", "json_object"))

        val request = Request.Builder()
            .url(candidate.provider.chat)
            .header("Authorization", "Bearer ${keyFor(candidate.provider)}")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        val body = http.newCall(request).execute().use { response ->
            val raw = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("HTTP ${response.code} ${raw.take(200)}")
            raw
        }

        val answer = JSONObject(body)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()

        DebugLog.dump(TAG, "${candidate.id} → απάντηση", answer)
        return rowsOf(answer, fileName, driveFileId)
    }

    private fun keyFor(provider: Provider) =
        if (provider == Provider.GROQ) groqKey else openRouterKey

    // -------------------------------------------------------------- ανάγνωση ---

    private fun rowsOf(answer: String, fileName: String, driveFileId: String): List<DebtEntity> {
        val json = firstObject(answer) ?: return emptyList()
        val debts = JSONObject(json).optJSONArray("debts") ?: return emptyList()

        return (0 until debts.length()).mapNotNull { at ->
            val row = debts.optJSONObject(at) ?: return@mapNotNull null
            val amount = row.optDouble("amount", 0.0)
            if (amount <= 0.0) return@mapNotNull null

            val kind = runCatching { DebtKind.valueOf(row.optString("kind")) }
                .getOrDefault(DebtKind.AADE)
            val month = row.optInt("periodMonth", 0).takeIf { it in 1..12 } ?: 0
            val year = row.optInt("periodYear", 0).takeIf { it in 2000..2100 } ?: 0
            val reference = row.optString("reference").filterNot { it.isWhitespace() }
            val person = row.optString("person").trim()

            DebtEntity(
                id = DebtEntity.idFor(kind, year, month, reference, person),
                kind = kind,
                periodMonth = month,
                periodYear = year,
                dueDay = day(row.optString("due")) ?: DebtEntity.defaultDue(kind, year, month),
                amount = amount,
                reference = reference,
                description = row.optString("description").trim()
                    .ifBlank { "Διαβάστηκε από μοντέλο" },
                personName = person,
                source = fileName,
                driveFileId = driveFileId,
            )
        }
    }

    /**
     * Το πρώτο ολοκληρωμένο αντικείμενο JSON μέσα στην απάντηση.
     *
     * Τα μοντέλα τυλίγουν την απάντηση σε ```json ή προσθέτουν μια πρόταση
     * καλωσορίσματος, ακόμη κι όταν τους ζητηθεί ρητά να μην το κάνουν.
     */
    private fun firstObject(answer: String): String? {
        val start = answer.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inText = false
        var escaped = false
        for (at in start until answer.length) {
            val ch = answer[at]
            when {
                escaped -> escaped = false
                ch == '\\' && inText -> escaped = true
                ch == '"' -> inText = !inText
                inText -> Unit
                ch == '{' -> depth++
                ch == '}' -> {
                    depth--
                    if (depth == 0) return answer.substring(start, at + 1)
                }
            }
        }
        return null
    }

    private fun day(raw: String): Long? = runCatching {
        LocalDate.parse(raw.trim(), DateTimeFormatter.ofPattern("d/M/uuuu")).toEpochDay()
    }.getOrNull()

    private companion object {
        const val TAG = "μοντέλο"
        const val MAX_PER_PROVIDER = 2
        const val MAX_FREE_POOL = 4
        const val FREE_ROUTER = "openrouter/free"
        const val MAX_TEXT = 12000
        const val MIN_CONTEXT = 8000

        val JSON = "application/json; charset=utf-8".toMediaType()

        /** Δεν κάνουν συνομιλία, ή είναι φίλτρα και όχι αναγνώστες. */
        val SKIP = listOf("whisper", "tts", "guard", "embed", "moderation", "rerank")

        /**
         * Οικογένειες που τα καταφέρνουν με ελληνικά και με JSON, κατά σειρά.
         *
         * Η σειρά βγήκε από μέτρηση πάνω στα ίδια τα παραστατικά, με τις τρεις
         * διατάξεις που έχει βγάλει το OCR: το qwen τις πέρασε και τις τρεις,
         * το gpt-oss έχασε την ταυτότητα οφειλής στη μία.
         */
        val FAMILIES = listOf(
            "qwen", "gpt-oss", "minimax", "llama", "glm", "deepseek", "mistral", "gemma",
        )

        val SYSTEM = """
            You read OCR text from Greek official documents and extract debts.
            Reply with ONE JSON object and nothing else:
            {"debts":[{"kind":"...","amount":0,"periodMonth":0,"periodYear":0,
            "due":"dd/MM/yyyy","reference":"","description":"","person":""}]}

            kind is one of AADE, IKA, TEKA, ADVERTISING, PAYROLL, PAYROLL_BONUS.
            - AADE: ΑΑΔΕ / TAXISnet «Σημείωμα για Πληρωμή»
            - IKA or TEKA: ΕΦΚΑ ΑΠΔ (TEKA when ΤΕΚΑ appears on the page)
            - ADVERTISING: διαφημιστικά τέλη / ΕΔΟΕΑΠ
            - PAYROLL: μισθοδοσία, one entry per employee, person = their name

            amount is what must be paid now, as a plain number. Greek documents
            write 1.234,56 which means 1234.56. For AADE prefer «Ποσό δόσης»
            over «Συνολικό ποσό οφειλής» when they differ.

            periodMonth and periodYear are the reference period
            («Ημερολογιακή Περίοδος»), NOT the payment deadline. For a range
            like 01/06/2026-30/06/2026 use month 6, year 2026.

            due is the payment deadline. reference is the «Ταυτότητα Οφειλής»
            copied whole, every digit of it joined with no spaces — usually 30
            digits, and it starts with the tax number, so do not drop that
            leading group. If there is no such code, use the RF code instead.
            description is the tax or contribution type, in Greek.

            The text may come from a two-column table flattened by OCR: every
            label first, then every value in the same order. Match them by
            position, not by distance.

            If the document is not a debt, reply {"debts":[]}.
        """.trimIndent()
    }
}
