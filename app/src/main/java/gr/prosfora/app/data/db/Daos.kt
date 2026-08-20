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
    @Query("SELECT * FROM offers ORDER BY dateEpochDay DESC, createdAt DESC")
    fun observeAll(): Flow<List<OfferWithDetails>>

    @Transaction
    @Query("SELECT * FROM offers WHERE id = :id")
    fun observeById(id: String): Flow<OfferWithDetails?>

    @Transaction
    @Query("SELECT * FROM offers WHERE id = :id")
    suspend fun getById(id: String): OfferWithDetails?

    @Upsert
    suspend fun upsert(offer: OfferEntity)

    @Query("DELETE FROM offers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE offers SET lastSentAt = :at, updatedAt = :at WHERE id = :id")
    suspend fun markSent(id: String, at: Long)
}

@Dao
interface SpaceDao {

    @Query("SELECT * FROM spaces WHERE offerId = :offerId ORDER BY position")
    fun observeForOffer(offerId: String): Flow<List<SpaceEntity>>

    @Upsert
    suspend fun upsert(space: SpaceEntity)

    @Delete
    suspend fun delete(space: SpaceEntity)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM spaces WHERE offerId = :offerId")
    suspend fun nextPosition(offerId: String): Int
}

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes WHERE offerId = :offerId ORDER BY position")
    fun observeForOffer(offerId: String): Flow<List<NoteEntity>>

    @Upsert
    suspend fun upsert(note: NoteEntity)

    @Delete
    suspend fun delete(note: NoteEntity)

    @Query("DELETE FROM notes WHERE offerId = :offerId AND text = :text")
    suspend fun deleteByText(offerId: String, text: String)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM notes WHERE offerId = :offerId")
    suspend fun nextPosition(offerId: String): Int
}

@Dao
interface NotePresetDao {

    @Query("SELECT * FROM note_presets ORDER BY position, useCount DESC")
    fun observeAll(): Flow<List<NotePresetEntity>>

    @Query("SELECT * FROM note_presets WHERE text = :text LIMIT 1")
    suspend fun findByText(text: String): NotePresetEntity?

    @Upsert
    suspend fun upsert(preset: NotePresetEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(presets: List<NotePresetEntity>)

    @Delete
    suspend fun delete(preset: NotePresetEntity)

    @Query("UPDATE note_presets SET useCount = useCount + 1 WHERE id = :id")
    suspend fun bumpUse(id: String)

    @Query("SELECT COALESCE(MAX(position), -1) + 1 FROM note_presets")
    suspend fun nextPosition(): Int
}
