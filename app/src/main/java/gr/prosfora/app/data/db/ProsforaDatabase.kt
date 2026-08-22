package gr.prosfora.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
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
    version = 5,
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
         * v2: soft deletes + updatedAt στα παιδιά, ώστε να μπορεί να γίνει
         * merge με το κοινόχρηστο Google Sheet.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL("ALTER TABLE offers ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE spaces ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE spaces ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE notes ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE notes ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            }
        }
        /** v3: στοιχεία επαφής και ιστορικό ειδοποίησης SMS/Viber. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL("ALTER TABLE offers ADD COLUMN customerName TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE offers ADD COLUMN customerPhone TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE offers ADD COLUMN notifiedAt INTEGER")
                connection.execSQL("ALTER TABLE offers ADD COLUMN notifiedVia TEXT")
            }
        }

        /** v4: ροή εργασιών (έναρξη/ολοκλήρωση) και αίτημα αξιολόγησης. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL("ALTER TABLE offers ADD COLUMN workStartDay INTEGER")
                connection.execSQL("ALTER TABLE offers ADD COLUMN workEndDay INTEGER")
                connection.execSQL("ALTER TABLE offers ADD COLUMN reviewSentAt INTEGER")
            }
        }

        /** v5: ισχύς προσφοράς και τρόπος πληρωμής. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL("ALTER TABLE offers ADD COLUMN validUntilDay INTEGER")
                connection.execSQL("ALTER TABLE offers ADD COLUMN paymentTerms TEXT NOT NULL DEFAULT ''")
            }
        }

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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5)
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
