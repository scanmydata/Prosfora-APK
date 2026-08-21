package gr.prosfora.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import java.util.UUID

/**
 * Αντιστοιχία με το AppSheet schema — βλ. docs/phase0-appsheet-schema.md.
 * Τα workarounds του AppSheet (Γενικό Σύνολο snapshot, Αποστολή_Trigger) δεν
 * μεταφέρονται: το σύνολο υπολογίζεται, το email στέλνεται απευθείας.
 */
@Entity(tableName = "offers")
data class OfferEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    /** Οδός / Περιοχή — το label της εγγραφής */
    val address: String = "",
    /** Ημερομηνία ως epoch day */
    val dateEpochDay: Long = 0L,
    /** Είδος (π.χ. "Χρωματισμός διαμερίσματος") */
    val kind: String = "",
    val email: String = "",
    /** Ονοματεπώνυμο της επαφής — μπαίνει στην προσοφώνηση της ειδοποίησης. */
    val customerName: String = "",
    /** Κινητό για ειδοποίηση με SMS ή Viber μετά την αποστολή του email. */
    val customerPhone: String = "",
    val status: OfferStatus = OfferStatus.CREATED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Πότε στάλθηκε τελευταία φορά email (null = ποτέ) */
    val lastSentAt: Long? = null,
    /** Πότε στάλθηκε ειδοποίηση για το email (SMS/Viber) και με ποιο μέσο. */
    val notifiedAt: Long? = null,
    val notifiedVia: String? = null,
    /**
     * Soft delete. Οι διαγραφές πρέπει να ταξιδεύουν μέχρι τις άλλες συσκευές:
     * αν σβήναμε τη γραμμή, ο επόμενος συγχρονισμός θα την ξανακατέβαζε από το
     * κοινόχρηστο Sheet σαν να μην έγινε τίποτα.
     */
    val deleted: Boolean = false,
)

enum class OfferStatus(val label: String) {
    CREATED("Δημιουργήθηκε"),
    IN_PROGRESS("Σε επεξεργασία"),
    COMPLETED("Ολοκληρώθηκε"),
    ;

    companion object {
        fun fromLabel(label: String): OfferStatus =
            entries.firstOrNull { it.label == label } ?: CREATED
    }
}

/** Χώροι_έργου — γραμμές ανάλυσης. Cascade delete όπως το IsAPartOf του AppSheet. */
@Entity(
    tableName = "spaces",
    foreignKeys = [
        ForeignKey(
            entity = OfferEntity::class,
            parentColumns = ["id"],
            childColumns = ["offerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("offerId")],
)
data class SpaceEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val offerId: String,
    /** Περιγραφή Χώρου */
    val description: String = "",
    /** Επιφάνεια (τ.μ.) */
    val area: Double = 0.0,
    /** Τιμή Μονάδος */
    val unitPrice: Double = 0.0,
    val position: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
) {
    /**
     * Σύνολο Γραμμής — ήταν app formula στο AppSheet.
     * Στρογγυλοποιείται στα 2 δεκαδικά ΠΡΙΝ αθροιστεί, όπως κάνει το AppSheet:
     * το άθροισμα των στρογγυλοποιημένων γραμμών είναι αυτό που τυπώνεται στο PDF
     * (π.χ. 1.892,99 €, ενώ το άθροισμα πλήρους ακρίβειας δίνει 1.892,98).
     */
    val lineTotal: Double get() = Math.round(area * unitPrice * 100.0) / 100.0
}

/** Λίστα_Παρατηρήσεων */
@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = OfferEntity::class,
            parentColumns = ["id"],
            childColumns = ["offerId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("offerId")],
)
data class NoteEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val offerId: String,
    @ColumnInfo(name = "text") val text: String = "",
    val position: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
)

/**
 * Βιβλιοθήκη έτοιμων σημειώσεων — δεν υπάρχει στο AppSheet.
 * Λύνει το «πιο εύκολη εισαγωγή σημειώσεων»: ένα tap αντί για φόρμα.
 */
@Entity(tableName = "note_presets")
data class NotePresetEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val text: String,
    /** Σειρά εμφάνισης στη λίστα επιλογών */
    val position: Int = 0,
    /** Πόσες φορές χρησιμοποιήθηκε — τα πιο συχνά ανεβαίνουν */
    val useCount: Int = 0,
)

data class OfferWithDetails(
    @Embedded val offer: OfferEntity,
    @Relation(parentColumn = "id", entityColumn = "offerId")
    val spacesRaw: List<SpaceEntity> = emptyList(),
    @Relation(parentColumn = "id", entityColumn = "offerId")
    val notesRaw: List<NoteEntity> = emptyList(),
) {
    /** Τα ζωντανά παιδιά — τα soft-deleted μένουν στη βάση για τον συγχρονισμό. */
    val spaces: List<SpaceEntity> get() = spacesRaw.filter { !it.deleted }.sortedBy { it.position }
    val notes: List<NoteEntity> get() = notesRaw.filter { !it.deleted }.sortedBy { it.position }

    /** Γενικό Σύνολο — ήταν virtual column + action snapshot στο AppSheet */
    val total: Double get() = spaces.sumOf { it.lineTotal }


    /**
     * Ο κανόνας Valid_If του AppSheet: χωρίς χώρους η προσφορά μένει
     * κλειδωμένη στο «Δημιουργήθηκε».
     */
    val availableStatuses: List<OfferStatus>
        get() = if (spaces.isEmpty()) {
            listOf(OfferStatus.CREATED)
        } else {
            listOf(OfferStatus.IN_PROGRESS, OfferStatus.COMPLETED)
        }

    val canSendEmail: Boolean
        get() = offer.email.isNotBlank() && offer.status == OfferStatus.COMPLETED

    /** Η ειδοποίηση έχει νόημα μόνο αφού φύγει το email και εφόσον υπάρχει κινητό. */
    val canNotify: Boolean
        get() = offer.customerPhone.isNotBlank() && offer.lastSentAt != null
}
