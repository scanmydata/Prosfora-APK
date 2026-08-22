package gr.prosfora.app.ui.offers

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import gr.prosfora.app.data.OfferRepository
import gr.prosfora.app.data.db.NoteEntity
import gr.prosfora.app.data.db.NotePresetEntity
import gr.prosfora.app.data.db.OfferEntity
import gr.prosfora.app.data.db.OfferStatus
import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.data.db.SpaceEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

class OffersViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = OfferRepository(app)

    val query = MutableStateFlow("")
    private val selectedId = MutableStateFlow<String?>(null)

    val offers = combine(repo.observeOffers(), query) { list, q ->
        if (q.isBlank()) {
            list
        } else {
            list.filter {
                it.offer.address.contains(q, ignoreCase = true) ||
                    it.offer.kind.contains(q, ignoreCase = true) ||
                    it.offer.email.contains(q, ignoreCase = true)
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val selectedOffer = selectedId
        .flatMapLatest { id -> if (id == null) kotlinx.coroutines.flow.flowOf(null) else repo.observeOffer(id) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val presets = repo.observePresets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Οι σημειώσεις που έχουν ήδη επιλεγεί για την τρέχουσα προσφορά. */
    val selectedNoteTexts = selectedOffer
        .map { details -> details?.notes?.map { it.text }?.toSet() ?: emptySet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    fun select(id: String?) { selectedId.value = id }

    fun onQueryChange(value: String) { query.value = value }

    fun createOffer(onCreated: (String) -> Unit) = viewModelScope.launch {
        val offer = OfferEntity(dateEpochDay = LocalDate.now().toEpochDay())
        repo.saveOffer(offer)
        onCreated(offer.id)
    }

    fun updateOffer(offer: OfferEntity) = viewModelScope.launch { repo.saveOffer(offer) }

    fun deleteOffer(id: String) = viewModelScope.launch { repo.deleteOffer(id) }

    fun markSent(id: String) = viewModelScope.launch { repo.markSent(id) }

    fun setWorkStart(offer: gr.prosfora.app.data.db.OfferEntity, day: Long?) =
        viewModelScope.launch { repo.setWorkStart(offer, day) }

    fun setWorkEnd(offer: gr.prosfora.app.data.db.OfferEntity, day: Long?) =
        viewModelScope.launch { repo.setWorkEnd(offer, day) }

    fun markReviewSent(offer: gr.prosfora.app.data.db.OfferEntity) =
        viewModelScope.launch { repo.markReviewSent(offer) }

    fun markNotified(id: String, via: String) =
        viewModelScope.launch { repo.markNotified(id, via) }

    fun setStatus(details: OfferWithDetails, status: OfferStatus) = viewModelScope.launch {
        if (status in details.availableStatuses) {
            repo.saveOffer(details.offer.copy(status = status))
        }
    }

    fun addSpace(offerId: String, description: String, area: Double, unitPrice: Double) =
        viewModelScope.launch { repo.addSpace(offerId, description, area, unitPrice) }

    fun updateSpace(space: SpaceEntity) = viewModelScope.launch { repo.updateSpace(space) }

    fun deleteSpace(space: SpaceEntity) = viewModelScope.launch { repo.deleteSpace(space) }

    fun toggleNote(offerId: String, text: String, enabled: Boolean) =
        viewModelScope.launch { repo.toggleNote(offerId, text, enabled) }

    fun addFreeNote(offerId: String, text: String, pin: Boolean) =
        viewModelScope.launch { repo.addFreeNote(offerId, text, pin) }

    fun deleteNote(note: NoteEntity) = viewModelScope.launch { repo.deleteNote(note) }

    fun deletePreset(preset: NotePresetEntity) = viewModelScope.launch { repo.deletePreset(preset) }
}
