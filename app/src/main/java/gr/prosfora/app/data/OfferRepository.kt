package gr.prosfora.app.data

import android.content.Context
import gr.prosfora.app.data.db.NoteEntity
import gr.prosfora.app.data.db.NotePresetEntity
import gr.prosfora.app.data.db.OfferEntity
import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.data.db.ProsforaDatabase
import gr.prosfora.app.data.db.SpaceEntity
import kotlinx.coroutines.flow.Flow

class OfferRepository(context: Context) {

    private val db = ProsforaDatabase.get(context)
    private val offers = db.offerDao()
    private val spaces = db.spaceDao()
    private val notes = db.noteDao()
    private val presets = db.notePresetDao()

    fun observeOffers(): Flow<List<OfferWithDetails>> = offers.observeAll()

    fun observeOffer(id: String): Flow<OfferWithDetails?> = offers.observeById(id)

    fun observePresets(): Flow<List<NotePresetEntity>> = presets.observeAll()

    suspend fun saveOffer(offer: OfferEntity) =
        offers.upsert(offer.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteOffer(id: String) = offers.softDelete(id, System.currentTimeMillis())

    suspend fun markSent(id: String) = offers.markSent(id, System.currentTimeMillis())

    suspend fun markNotified(id: String, via: String) =
        offers.markNotified(id, System.currentTimeMillis(), via)

    suspend fun addSpace(offerId: String, description: String, area: Double, unitPrice: Double) {
        spaces.upsert(
            SpaceEntity(
                offerId = offerId,
                description = description,
                area = area,
                unitPrice = unitPrice,
                position = spaces.nextPosition(offerId),
            ),
        )
        touch(offerId)
    }

    suspend fun updateSpace(space: SpaceEntity) {
        spaces.upsert(space.copy(updatedAt = System.currentTimeMillis()))
        touch(space.offerId)
    }

    suspend fun deleteSpace(space: SpaceEntity) {
        spaces.softDelete(space.id, System.currentTimeMillis())
        touch(space.offerId)
    }

    /**
     * Toggle μιας έτοιμης σημείωσης πάνω στην προσφορά — ένα tap αντί για φόρμα.
     * Κρατάει και μετρητή χρήσης ώστε οι συχνές να ανεβαίνουν.
     */
    suspend fun toggleNote(offerId: String, text: String, enabled: Boolean) {
        if (enabled) {
            notes.upsert(
                NoteEntity(offerId = offerId, text = text, position = notes.nextPosition(offerId)),
            )
            presets.findByText(text)?.let { presets.bumpUse(it.id) }
        } else {
            notes.softDeleteByText(offerId, text, System.currentTimeMillis())
        }
        touch(offerId)
    }

    suspend fun addFreeNote(offerId: String, text: String, pinAsPreset: Boolean) {
        notes.upsert(
            NoteEntity(offerId = offerId, text = text, position = notes.nextPosition(offerId)),
        )
        if (pinAsPreset && presets.findByText(text) == null) {
            presets.upsert(NotePresetEntity(text = text, position = presets.nextPosition()))
        }
        touch(offerId)
    }

    suspend fun deleteNote(note: NoteEntity) {
        notes.softDelete(note.id, System.currentTimeMillis())
        touch(note.offerId)
    }

    suspend fun deletePreset(preset: NotePresetEntity) = presets.delete(preset)

    private suspend fun touch(offerId: String) {
        offers.getById(offerId)?.let { saveOffer(it.offer) }
    }
}
