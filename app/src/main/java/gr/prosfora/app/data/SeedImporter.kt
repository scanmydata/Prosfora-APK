package gr.prosfora.app.data

import android.content.Context
import gr.prosfora.app.data.db.NoteEntity
import gr.prosfora.app.data.db.NotePresetEntity
import gr.prosfora.app.data.db.OfferEntity
import gr.prosfora.app.data.db.OfferStatus
import gr.prosfora.app.data.db.ProsforaDatabase
import gr.prosfora.app.data.db.SpaceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Εισαγωγή των υπαρχόντων δεδομένων από το AppSheet.
 *
 * Το `assets/seed.json` παράγεται από το `migration/import_sheet.py` πάνω στο
 * export του Google Sheet. Τα IDs του AppSheet διατηρούνται, οπότε η εισαγωγή
 * είναι idempotent: αν τρέξει δεύτερη φορά, οι ίδιες εγγραφές απλώς
 * ξαναγράφονται αντί να διπλασιαστούν.
 */
object SeedImporter {

    data class Result(val offers: Int, val spaces: Int, val notes: Int, val presets: Int)

    suspend fun importFromAssets(context: Context): Result = withContext(Dispatchers.IO) {
        val json = context.assets.open("seed.json").bufferedReader().use { it.readText() }
        val root = JSONObject(json)
        val db = ProsforaDatabase.get(context)

        val offersArray = root.optJSONArray("offers")
        var offers = 0
        if (offersArray != null) {
            for (i in 0 until offersArray.length()) {
                val item = offersArray.getJSONObject(i)
                db.offerDao().upsert(
                    OfferEntity(
                        id = item.getString("id"),
                        address = item.optString("address"),
                        dateEpochDay = item.optLong("dateEpochDay"),
                        kind = item.optString("kind"),
                        email = item.optString("email"),
                        status = runCatching { OfferStatus.valueOf(item.optString("status")) }
                            .getOrDefault(OfferStatus.CREATED),
                    ),
                )
                offers++
            }
        }

        val spacesArray = root.optJSONArray("spaces")
        var spaces = 0
        if (spacesArray != null) {
            for (i in 0 until spacesArray.length()) {
                val item = spacesArray.getJSONObject(i)
                db.spaceDao().upsert(
                    SpaceEntity(
                        id = item.getString("id"),
                        offerId = item.getString("offerId"),
                        description = item.optString("description"),
                        area = item.optDouble("area", 0.0),
                        unitPrice = item.optDouble("unitPrice", 0.0),
                        position = item.optInt("position"),
                    ),
                )
                spaces++
            }
        }

        val notesArray = root.optJSONArray("notes")
        var notes = 0
        if (notesArray != null) {
            for (i in 0 until notesArray.length()) {
                val item = notesArray.getJSONObject(i)
                db.noteDao().upsert(
                    NoteEntity(
                        id = item.getString("id"),
                        offerId = item.getString("offerId"),
                        text = item.optString("text"),
                        position = item.optInt("position"),
                    ),
                )
                notes++
            }
        }

        // Οι σημειώσεις που επαναλαμβάνονταν γίνονται έτοιμες επιλογές, χωρίς διπλά
        val presetsArray = root.optJSONArray("presets")
        var presets = 0
        if (presetsArray != null) {
            for (i in 0 until presetsArray.length()) {
                val item = presetsArray.getJSONObject(i)
                val text = item.optString("text")
                if (text.isBlank() || db.notePresetDao().findByText(text) != null) continue
                db.notePresetDao().upsert(
                    NotePresetEntity(
                        text = text,
                        position = db.notePresetDao().nextPosition(),
                        useCount = item.optInt("useCount"),
                    ),
                )
                presets++
            }
        }

        Result(offers, spaces, notes, presets)
    }
}
