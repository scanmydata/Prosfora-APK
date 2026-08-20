package gr.prosfora.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class Converters {
    @TypeConverter
    fun statusToString(status: OfferStatus): String = status.name

    @TypeConverter
    fun stringToStatus(value: String): OfferStatus =
        runCatching { OfferStatus.valueOf(value) }.getOrDefault(OfferStatus.CREATED)
}

@Database(
    entities = [OfferEntity::class, SpaceEntity::class, NoteEntity::class, NotePresetEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class ProsforaDatabase : RoomDatabase() {

    abstract fun offerDao(): OfferDao
    abstract fun spaceDao(): SpaceDao
    abstract fun noteDao(): NoteDao
    abstract fun notePresetDao(): NotePresetDao

    companion object {
        /**
         * Οι σημειώσεις που επαναλαμβάνονται σε κάθε προσφορά — από το δείγμα PDF
         * και το EnumList "Παρατηρήσεις Έργου" του AppSheet.
         */
        val DEFAULT_PRESETS = listOf(
            "Στην προσφορά δεν περιλαμβάνεται ο ΦΠΑ τιμολογίου.",
            "Η προσφορά περιλαμβάνει την εργασία και τα υλικά.",
            "Τα χρώματα που θα χρησιμοποιηθούν εσωτερικά θα είναι άοσμα πλαστικά της vivechrom.",
            "Τα δάπεδα θα καλυφθούν με χαρτόνι και τα σοβατεπιά με χαρτοταινία.",
        )

        @Volatile
        private var instance: ProsforaDatabase? = null

        fun get(context: Context): ProsforaDatabase =
            instance ?: synchronized(this) { instance ?: build(context).also { instance = it } }

        private fun build(context: Context): ProsforaDatabase {
            lateinit var db: ProsforaDatabase
            db = Room.databaseBuilder(
                context.applicationContext,
                ProsforaDatabase::class.java,
                "prosfora.db",
            )
                .addCallback(object : Callback() {
                    override fun onCreate(connection: SupportSQLiteDatabase) {
                        super.onCreate(connection)
                        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                            db.notePresetDao().insertAll(
                                DEFAULT_PRESETS.mapIndexed { i, text ->
                                    NotePresetEntity(text = text, position = i)
                                },
                            )
                        }
                    }
                })
                .build()
            return db
        }
    }
}
