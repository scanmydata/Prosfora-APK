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
    val status: OfferStatus = OfferStatus.CREATED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Πότε στάλθηκε τελευταία φορά email (null = ποτέ) */
    val lastSentAt: Long? = null,
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
    val spaces: List<SpaceEntity> = emptyList(),
    @Relation(parentColumn = "id", entityColumn = "offerId")
    val notes: List<NoteEntity> = emptyList(),
) {
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
}
