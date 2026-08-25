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
 * Τα IDs έρχονται έτοιμα από το script και βγαίνουν από τη διαδρομή του κάθε
 * αρχείου: αν η εισαγωγή ξανατρέξει, οι ίδιες προσφορές **ενημερώνονται** αντί
 * να διπλασιαστούν.
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

    /**
     * Γράφει τα πάντα σε μία δοσοληψία: ή μπαίνει όλο το ιστορικό ή τίποτα.
     * Χωρίς αυτό, μια αποτυχία στη μέση θα άφηνε προσφορές χωρίς τους χώρους τους.
     */
    suspend fun store(
        context: Context,
        bundle: Bundle,
        onProgress: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val db = ProsforaDatabase.get(context)
        db.withTransaction {
            onProgress("Προσφορές…")
            bundle.offers.chunked(CHUNK).forEach { db.offerDao().upsertAll(it) }
            onProgress("Χώροι…")
            bundle.spaces.chunked(CHUNK).forEach { db.spaceDao().upsertAll(it) }
            onProgress("Σημειώσεις…")
            bundle.notes.chunked(CHUNK).forEach { db.noteDao().upsertAll(it) }
        }
    }

    private const val KIND = "prosfora-history"
    private const val CHUNK = 400
}
