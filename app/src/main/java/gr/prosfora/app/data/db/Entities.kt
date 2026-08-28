package gr.prosfora.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import java.util.UUID

@Entity(tableName = "offers")
data class OfferEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val address: String = "",
    val dateEpochDay: Long = 0L,
    val kind: String = "",
    val email: String = "",
    val customerName: String = "",
    val customerLastName: String = "",
    val customerGender: Gender = Gender.UNKNOWN,
    val customerPhone: String = "",
    val status: OfferStatus = OfferStatus.CREATED,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastSentAt: Long? = null,
    val notifiedAt: Long? = null,
    val notifiedVia: String? = null,
    val workStartDay: Long? = null,
    val workEndDay: Long? = null,
    val reviewSentAt: Long? = null,
    val validUntilDay: Long? = null,
    val paymentTerms: String = "",
    val vatIncluded: Boolean = false,
    val scaffolding: Boolean = false,
    val scaffoldingCost: Double = 0.0,
    val permit: Boolean = false,
    val permitCost: Double = 0.0,
    /** Προαιρετικό νέο πρόσθετο κόστος που ορίζει ο χρήστης με όνομα και τιμή. */
    val customExtraName: String = "",
    val customExtraCost: Double = 0.0,
    val source: String = "",
    val deleted: Boolean = false,
)

enum class Gender(val label: String, val title: String) {
    UNKNOWN("Δεν ξέρω", ""),
    MALE("Άνδρας", "κύριε"),
    FEMALE("Γυναίκα", "κυρία"),
}

const val VAT_RATE = 0.24

enum class JobStage(val label: String) {
    NOT_A_JOB("—"),
    PENDING("Χωρίς έναρξη"),
    IN_PROGRESS("Σε εξέλιξη"),
    FINISHED("Ολοκληρώθηκε"),
}

enum class OfferStatus(val label: String) {
    CREATED("Δημιουργήθηκε"),
    IN_PROGRESS("Σε επεξεργασία"),
    COMPLETED("Ολοκληρώθηκε"),
    ;

    companion object {
        fun fromLabel(label: String): OfferStatus = entries.firstOrNull { it.label == label } ?: CREATED
    }
}

@Entity(
    tableName = "spaces",
    foreignKeys = [
        ForeignKey(entity = OfferEntity::class, parentColumns = ["id"], childColumns = ["offerId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("offerId")],
)
data class SpaceEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val offerId: String,
    val description: String = "",
    val area: Double = 0.0,
    val unitPrice: Double = 0.0,
    val position: Int = 0,
    val updatedAt: Long = System.currentTimeMillis(),
    val deleted: Boolean = false,
) {
    val lineTotal: Double get() = Math.round(area * unitPrice * 100.0) / 100.0
}

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(entity = OfferEntity::class, parentColumns = ["id"], childColumns = ["offerId"], onDelete = ForeignKey.CASCADE),
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

@Entity(tableName = "note_presets")
data class NotePresetEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val text: String,
    val position: Int = 0,
    val useCount: Int = 0,
)

data class OfferWithDetails(
    @Embedded val offer: OfferEntity,
    @Relation(parentColumn = "id", entityColumn = "offerId") val spacesRaw: List<SpaceEntity> = emptyList(),
    @Relation(parentColumn = "id", entityColumn = "offerId") val notesRaw: List<NoteEntity> = emptyList(),
) {
    val spaces: List<SpaceEntity> get() = spacesRaw.filter { !it.deleted }.sortedBy { it.position }
    val notes: List<NoteEntity> get() = notesRaw.filter { !it.deleted }.sortedBy { it.position }
    val linesTotal: Double get() = spaces.sumOf { it.lineTotal }
    val scaffoldingCost: Double get() = if (offer.scaffolding) offer.scaffoldingCost else 0.0
    val permitCost: Double get() = if (offer.permit) offer.permitCost else 0.0
    val customExtraCost: Double get() = if (offer.customExtraName.isNotBlank()) offer.customExtraCost else 0.0
    val total: Double get() = linesTotal + scaffoldingCost + permitCost + customExtraCost
    val vatAmount: Double get() = if (offer.vatIncluded) Math.round(total * VAT_RATE * 100.0) / 100.0 else 0.0
    val grandTotal: Double get() = total + vatAmount
    val year: Int get() = java.time.LocalDate.ofEpochDay(offer.dateEpochDay).year
    val jobStage: JobStage get() = when {
        offer.status != OfferStatus.COMPLETED -> JobStage.NOT_A_JOB
        offer.workEndDay != null -> JobStage.FINISHED
        offer.workStartDay != null -> JobStage.IN_PROGRESS
        else -> JobStage.PENDING
    }
    fun daysSinceFinish(today: java.time.LocalDate = java.time.LocalDate.now()): Long? = offer.workEndDay?.let { today.toEpochDay() - it }
    fun reviewDue(delayDays: Int, today: java.time.LocalDate = java.time.LocalDate.now()): Boolean = offer.reviewSentAt == null && (daysSinceFinish(today) ?: -1) >= delayDays
    val paymentLines: List<String> get() = offer.paymentTerms.lines().map { it.trim() }.filter { it.isNotBlank() }
    val fullName: String get() = listOf(offer.customerName, offer.customerLastName).filter { it.isNotBlank() }.joinToString(" ")
    val imported: Boolean get() = offer.source.isNotBlank()
    fun expired(today: java.time.LocalDate = java.time.LocalDate.now()): Boolean = offer.validUntilDay?.let { it < today.toEpochDay() } == true
    val availableStatuses: List<OfferStatus> get() = if (spaces.isEmpty()) listOf(OfferStatus.CREATED) else listOf(OfferStatus.IN_PROGRESS, OfferStatus.COMPLETED)
    val canSendEmail: Boolean get() = offer.email.isNotBlank() && offer.status == OfferStatus.COMPLETED
    val canNotify: Boolean get() = offer.customerPhone.isNotBlank() && offer.lastSentAt != null
}
