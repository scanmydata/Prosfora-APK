package gr.prosfora.app.data

import android.content.Context
import androidx.room.withTransaction
import gr.prosfora.app.data.db.NoteEntity
import gr.prosfora.app.data.db.OfferEntity
import gr.prosfora.app.data.db.OfferStatus
import gr.prosfora.app.data.db.ProsforaDatabase
import gr.prosfora.app.data.db.SpaceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate

/**
 * Εισαγωγή του ιστορικού από τα παλιά φύλλα Excel.
 *
 * Τα `.xls` του Excel 97 δεν διαβάζονται στο Android χωρίς ολόκληρο το Apache
 * POI, οπότε τη μετατροπή την κάνει ο υπολογιστής: το
 * `migration/import_history.py` σαρώνει τον φάκελο «ΕΠΙΜΕΤΡΗΣΕΙΣ ΔΟΥΛΕΙΕΣ» και
 * γράφει ένα JSON. Εδώ απλώς διαβάζεται και μπαίνει στη βάση.
 *
 * Τα IDs έρχονται έτοιμα από το script και αντιστοιχούν στη διαδρομή του κάθε
 * αρχείου. Έτσι μια δεύτερη εισαγωγή αναγνωρίζει τι υπάρχει ήδη — και ξεχωρίζει
 * ποια από αυτά άλλαξαν μέσα στο φύλλο.
 */
object HistoryImporter {

    /** Τι περιέχει το αρχείο — δείχνεται στον χρήστη πριν γραφτεί τίποτα. */
    data class Bundle(
        val source: String,
        val offers: List<OfferEntity>,
        val spaces: List<SpaceEntity>,
        val notes: List<NoteEntity>,
        val approximateDates: Int,
    ) {
        val total: Double
            get() = spaces.sumOf { Math.round(it.area * it.unitPrice * 100.0) / 100.0 }

        val years: String
            get() {
                val all = offers.map { LocalDate.ofEpochDay(it.dateEpochDay).year }
                val from = all.minOrNull() ?: return "—"
                val to = all.maxOrNull() ?: return "—"
                return if (from == to) "$from" else "$from–$to"
            }

        val completed: Int get() = offers.count { it.status == OfferStatus.COMPLETED }
    }

    /**
     * Πώς συγκρίνεται το αρχείο με ό,τι υπάρχει ήδη στη βάση.
     *
     * Ο χωρισμός σε «νέες» και «άλλαξαν» υπάρχει επειδή τα φύλλα δουλεύονται
     * ξανά και ξανά στη θέση τους. Χωρίς αυτόν, ή θα χάνονταν οι διορθώσεις του
     * Excel ή θα σβήνονταν αλλαγές που έγιναν από την εφαρμογή.
     */
    data class Plan(
        val fresh: Set<String>,
        val changed: Set<String>,
        val unchanged: Int,
    )

    class InvalidFile(message: String) : IllegalArgumentException(message)

    suspend fun parse(text: String): Bundle = withContext(Dispatchers.Default) {
        val root = runCatching { JSONObject(text) }
            .getOrElse { throw InvalidFile("Το αρχείο δεν είναι έγκυρο JSON") }
        if (root.optString("kind") != KIND) {
            throw InvalidFile("Δεν είναι αρχείο ιστορικού προσφορών")
        }

        val now = System.currentTimeMillis()
        var approximate = 0

        val offersArray = root.optJSONArray("offers")
            ?: throw InvalidFile("Λείπουν οι προσφορές")
        val offers = (0 until offersArray.length()).map { i ->
            val item = offersArray.getJSONObject(i)
            if (item.optBoolean("approximateDate")) approximate++
            OfferEntity(
                id = item.getString("id"),
                address = item.optString("address"),
                kind = item.optString("kind"),
                dateEpochDay = item.optLong("dateEpochDay"),
                status = runCatching { OfferStatus.valueOf(item.optString("status")) }
                    .getOrDefault(OfferStatus.COMPLETED),
                validUntilDay = item.optLong("validUntilDay", -1L).takeIf { it >= 0 },
                paymentTerms = item.optString("paymentTerms"),
                source = item.optString("source").ifBlank { "ιστορικό" },
                createdAt = now,
                updatedAt = now,
            )
        }

        val spacesArray = root.optJSONArray("spaces")
        val spaces = (0 until (spacesArray?.length() ?: 0)).map { i ->
            val item = spacesArray!!.getJSONObject(i)
            SpaceEntity(
                id = item.getString("id"),
                offerId = item.getString("offerId"),
                description = item.optString("description"),
                area = item.optDouble("area", 0.0),
                unitPrice = item.optDouble("unitPrice", 0.0),
                position = item.optInt("position"),
                updatedAt = now,
            )
        }

        val notesArray = root.optJSONArray("notes")
        val notes = (0 until (notesArray?.length() ?: 0)).map { i ->
            val item = notesArray!!.getJSONObject(i)
            NoteEntity(
                id = item.getString("id"),
                offerId = item.getString("offerId"),
                text = item.optString("text"),
                position = item.optInt("position"),
                updatedAt = now,
            )
        }

        Bundle(
            source = root.optString("sourceFolder", "ιστορικό"),
            offers = offers,
            spaces = spaces,
            notes = notes,
            approximateDates = approximate,
        )
    }

    /** Συγκρίνει το αρχείο με τη βάση, χωρίς να γράψει τίποτα. */
    suspend fun plan(context: Context, bundle: Bundle): Plan = withContext(Dispatchers.IO) {
        val db = ProsforaDatabase.get(context)
        val stored = db.offerDao().allForSync().associateBy { it.id }
        val storedSpaces = db.spaceDao().allForSync()
            .filter { !it.deleted }.groupBy { it.offerId }
        val storedNotes = db.noteDao().allForSync()
            .filter { !it.deleted }.groupBy { it.offerId }

        val incomingSpaces = bundle.spaces.groupBy { it.offerId }
        val incomingNotes = bundle.notes.groupBy { it.offerId }

        val fresh = mutableSetOf<String>()
        val changed = mutableSetOf<String>()
        var unchanged = 0

        bundle.offers.forEach { offer ->
            val existing = stored[offer.id]
            if (existing == null || existing.deleted) {
                fresh += offer.id
                return@forEach
            }
            val before = signature(
                existing,
                storedSpaces[offer.id].orEmpty(),
                storedNotes[offer.id].orEmpty(),
            )
            val after = signature(
                offer,
                incomingSpaces[offer.id].orEmpty(),
                incomingNotes[offer.id].orEmpty(),
            )
            if (before == after) unchanged++ else changed += offer.id
        }

        Plan(fresh = fresh, changed = changed, unchanged = unchanged)
    }

    /**
     * Γράφει τις προσφορές με τα δοσμένα [ids] σε μία δοσοληψία: ή μπαίνουν όλες
     * ή καμία. Χωρίς αυτό, μια αποτυχία στη μέση θα άφηνε προσφορές χωρίς τους
     * χώρους τους.
     */
    suspend fun store(
        context: Context,
        bundle: Bundle,
        ids: Set<String>,
        onProgress: (String) -> Unit = {},
    ): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        val db = ProsforaDatabase.get(context)
        val now = System.currentTimeMillis()

        val offers = bundle.offers.filter { it.id in ids }
        val spaces = bundle.spaces.filter { it.offerId in ids }
        val notes = bundle.notes.filter { it.offerId in ids }

        db.withTransaction {
            onProgress("Προσφορές…")
            offers.chunked(CHUNK).forEach { db.offerDao().upsertAll(it) }

            // Ό,τι είχε η προσφορά πριν σβήνεται πρώτα: το φύλλο είναι η πηγή
            // αλήθειας και μια γραμμή που διαγράφηκε στο Excel δεν πρέπει να
            // επιβιώσει στη βάση επειδή απλώς δεν ξαναήρθε.
            onProgress("Χώροι…")
            ids.forEach { db.spaceDao().softDeleteForOffer(it, now) }
            spaces.chunked(CHUNK).forEach { db.spaceDao().upsertAll(it) }

            onProgress("Σημειώσεις…")
            ids.forEach { db.noteDao().softDeleteForOffer(it, now) }
            notes.chunked(CHUNK).forEach { db.noteDao().upsertAll(it) }
        }
        offers.size
    }

    /**
     * Ό,τι μετράει για να πούμε ότι μια προσφορά «άλλαξε». Το `updatedAt` και τα
     * ιστορικά αποστολών μένουν απ' έξω επίτηδες: αλλάζουν χωρίς να αλλάζει το
     * περιεχόμενο του φύλλου.
     */
    private fun signature(
        offer: OfferEntity,
        spaces: List<SpaceEntity>,
        notes: List<NoteEntity>,
    ): String = buildString {
        append(offer.address).append(SEP)
        append(offer.kind).append(SEP)
        append(offer.dateEpochDay).append(SEP)
        append(offer.validUntilDay).append(SEP)
        append(offer.paymentTerms).append(SEP)
        append(offer.status.name).append(SEP)
        // Η πηγή μπαίνει κι αυτή: προσφορές που εισήχθησαν πριν υπάρξει η στήλη
        // την έχουν κενή, και πρέπει να συμπληρωθεί με μια επανεισαγωγή
        append(offer.source).append(SEP)
        spaces.sortedBy { it.position }.forEach {
            append(it.description).append(':')
            append(it.area).append(':')
            append(it.unitPrice).append(SEP)
        }
        append(SEP)
        notes.sortedBy { it.position }.forEach { append(it.text).append(SEP) }
    }

    /** Διαχωριστικό υπογραφής — δεν εμφανίζεται σε κείμενο προσφοράς. */
    private const val SEP = "<|>"
    private const val KIND = "prosfora-history"
    private const val CHUNK = 400
}
