package gr.prosfora.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface OfferDao {
    @Transaction
    @Query("SELECT * FROM offers WHERE deleted = 0 ORDER BY dateEpochDay DESC, createdAt DESC")
    fun observeAll(): Flow<List<OfferWithDetails>>
    @Transaction
    @Query("SELECT * FROM offers WHERE id = :id")
    fun observeById(id: String): Flow<OfferWithDetails?>
    @Transaction
    @Query("SELECT * FROM offers WHERE id = :id")
    suspend fun getById(id: String): OfferWithDetails?
    @Upsert suspend fun upsert(offer: OfferEntity)
    @Upsert suspend fun upsertAll(items: List<OfferEntity>)
    @Query("UPDATE offers SET deleted = 1, updatedAt = :at WHERE id = :id") suspend fun softDelete(id: String, at: Long)
    @Query("SELECT * FROM offers") suspend fun allForSync(): List<OfferEntity>
    @Query("SELECT id FROM offers") suspend fun allIds(): List<String>
    @Query("UPDATE offers SET lastSentAt = :at, updatedAt = :at WHERE id = :id") suspend fun markSent(id: String, at: Long)
    @Query("UPDATE offers SET notifiedAt = :at, notifiedVia = :via, updatedAt = :at WHERE id = :id") suspend fun markNotified(id: String, at: Long, via: String)
}

@Dao
interface SpaceDao {
    @Query("SELECT * FROM spaces WHERE offerId = :offerId AND deleted = 0 ORDER BY position") fun observeForOffer(offerId: String): Flow<List<SpaceEntity>>
    @Query("SELECT * FROM spaces") suspend fun allForSync(): List<SpaceEntity>
    @Upsert suspend fun upsert(space: SpaceEntity)
    @Upsert suspend fun upsertAll(items: List<SpaceEntity>)
    @Query("UPDATE spaces SET deleted = 1, updatedAt = :at WHERE id = :id") suspend fun softDelete(id: String, at: Long)
    @Query("UPDATE spaces SET deleted = 1, updatedAt = :at WHERE offerId = :offerId AND deleted = 0") suspend fun softDeleteForOffer(offerId: String, at: Long)
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM spaces WHERE offerId = :offerId AND deleted = 0") suspend fun nextPosition(offerId: String): Int
}

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE offerId = :offerId AND deleted = 0 ORDER BY position") fun observeForOffer(offerId: String): Flow<List<NoteEntity>>
    @Query("SELECT * FROM notes") suspend fun allForSync(): List<NoteEntity>
    @Upsert suspend fun upsert(note: NoteEntity)
    @Upsert suspend fun upsertAll(items: List<NoteEntity>)
    @Query("UPDATE notes SET deleted = 1, updatedAt = :at WHERE id = :id") suspend fun softDelete(id: String, at: Long)
    @Query("UPDATE notes SET deleted = 1, updatedAt = :at WHERE offerId = :offerId AND deleted = 0") suspend fun softDeleteForOffer(offerId: String, at: Long)
    @Query("UPDATE notes SET deleted = 1, updatedAt = :at WHERE offerId = :offerId AND text = :text AND deleted = 0") suspend fun softDeleteByText(offerId: String, text: String, at: Long)
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM notes WHERE offerId = :offerId AND deleted = 0") suspend fun nextPosition(offerId: String): Int
}

@Dao
interface NotePresetDao {
    @Query("SELECT * FROM note_presets ORDER BY position, useCount DESC") fun observeAll(): Flow<List<NotePresetEntity>>
    @Query("SELECT * FROM note_presets WHERE text = :text LIMIT 1") suspend fun findByText(text: String): NotePresetEntity?
    @Upsert suspend fun upsert(preset: NotePresetEntity)
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(presets: List<NotePresetEntity>)
    @Delete suspend fun delete(preset: NotePresetEntity)
    @Query("UPDATE note_presets SET useCount = useCount + 1 WHERE id = :id") suspend fun bumpUse(id: String)
    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM note_presets") suspend fun nextPosition(): Int
}

@Dao
interface DebtDao {
    @Query("SELECT * FROM debts WHERE deleted = 0 ORDER BY periodYear DESC, periodMonth DESC, kind") fun observeAll(): Flow<List<DebtEntity>>
    @Query("SELECT * FROM debts WHERE id = :id") suspend fun getById(id: String): DebtEntity?
    @Upsert suspend fun upsert(debt: DebtEntity)
    @Upsert suspend fun upsertAll(items: List<DebtEntity>)
    @Query("UPDATE debts SET deleted = 1, updatedAt = :at WHERE id = :id") suspend fun softDelete(id: String, at: Long)
    @Query("UPDATE debts SET paid = :paid, paidAt = :paidAt, updatedAt = :at WHERE id = :id") suspend fun markPaid(id: String, paid: Boolean, paidAt: Long?, at: Long)
    @Query("SELECT * FROM debts") suspend fun allForSync(): List<DebtEntity>
    @Query("SELECT DISTINCT driveFileId FROM debts WHERE driveFileId != ''") suspend fun importedFileIds(): List<String>
}

@Dao
interface EmployeeDao {
    @Query("SELECT * FROM employees WHERE deleted = 0 ORDER BY name") fun observeAll(): Flow<List<EmployeeEntity>>
    @Query("SELECT * FROM employees WHERE amIka = :amIka LIMIT 1") suspend fun findByAmIka(amIka: String): EmployeeEntity?
    @Upsert suspend fun upsert(employee: EmployeeEntity)
    @Upsert suspend fun upsertAll(employees: List<EmployeeEntity>)
    @Query("SELECT * FROM employees") suspend fun allForSync(): List<EmployeeEntity>
    @Query("UPDATE employees SET deleted = 1, updatedAt = :at WHERE id = :id") suspend fun softDelete(id: String, at: Long)
    @Query("UPDATE employees SET deleted = 1, updatedAt = :at") suspend fun softDeleteAll(at: Long)
}
