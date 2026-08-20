package gr.prosfora.app.data

import java.time.LocalDate

/**
 * ΠΡΟΣΩΡΙΝΟ MODEL — Φάση 0 (καταγραφή του AppSheet schema) δεν έχει ολοκληρωθεί ακόμη.
 * Μόλις έχουμε τα πραγματικά columns/types από τον AppSheet editor, αυτό το αρχείο
 * αντικαθίσταται με το πραγματικό schema και συνδέεται με Firestore (Φάση 2/3).
 */
data class Offer(
    val id: String,
    val customer: String,
    val title: String,
    val date: LocalDate,
    val amount: Double,
    val status: OfferStatus,
)

enum class OfferStatus(val label: String) {
    IN_PROGRESS("Σε επεξεργασία"),
    COMPLETED("Ολοκληρώθηκε"),
}

/** Placeholder in-memory source, ώστε το CI build να παράγει τρέξιμο APK για δοκιμή pipeline. */
object SampleData {
    val offers: List<Offer> = listOf(
        Offer("1", "Πελάτης Α", "Προσφορά εγκατάστασης", LocalDate.of(2026, 8, 12), 1450.0, OfferStatus.IN_PROGRESS),
        Offer("2", "Πελάτης Β", "Συντήρηση ετήσια", LocalDate.of(2026, 8, 5), 380.0, OfferStatus.COMPLETED),
        Offer("3", "Πελάτης Γ", "Αντικατάσταση εξοπλισμού", LocalDate.of(2026, 7, 28), 2790.0, OfferStatus.COMPLETED),
    )
}
