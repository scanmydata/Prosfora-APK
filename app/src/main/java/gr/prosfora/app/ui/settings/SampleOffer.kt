package gr.prosfora.app.ui.settings

import gr.prosfora.app.data.db.NoteEntity
import gr.prosfora.app.data.db.OfferEntity
import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.data.db.SpaceEntity
import java.time.LocalDate

/**
 * Πλασματική προσφορά για προεπισκοπήσεις — προτύπου PDF και προτύπων μηνυμάτων.
 * Δεν γράφεται ποτέ στη βάση.
 */
object SampleOffer {

    val value: OfferWithDetails by lazy {
        val offer = OfferEntity(
            id = "preview",
            address = "Δείγμα 12, Αθήνα",
            dateEpochDay = LocalDate.now().toEpochDay(),
            kind = "Διαμέρισμα",
            customerName = "Μαρία Παπαδοπούλου",
            email = "sample@example.com",
            customerPhone = "6941234567",
        )
        OfferWithDetails(
            offer = offer,
            spacesRaw = listOf(
                SpaceEntity(offerId = offer.id, description = "Σαλόνι", area = 45.0, unitPrice = 4.8, position = 0),
                SpaceEntity(offerId = offer.id, description = "Κουζίνα", area = 18.5, unitPrice = 4.8, position = 1),
                SpaceEntity(offerId = offer.id, description = "Πόρτες ριπολίνα", area = 4.0, unitPrice = 55.0, position = 2),
            ),
            notesRaw = listOf(
                NoteEntity(offerId = offer.id, text = "Στην προσφορά δεν περιλαμβάνεται ο ΦΠΑ τιμολογίου.", position = 0),
                NoteEntity(offerId = offer.id, text = "Η προσφορά περιλαμβάνει την εργασία και τα υλικά.", position = 1),
            ),
        )
    }
}
