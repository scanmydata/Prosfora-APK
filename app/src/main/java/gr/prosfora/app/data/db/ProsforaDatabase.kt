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

    @TypeConverter
    fun genderToString(gender: Gender): String = gender.name

    @TypeConverter
    fun stringToGender(value: String): Gender =
        runCatching { Gender.valueOf(value) }.getOrDefault(Gender.UNKNOWN)

    @TypeConverter
    fun debtKindToString(kind: DebtKind): String = kind.name

    @TypeConverter
    fun stringToDebtKind(value: String): DebtKind =
        runCatching { DebtKind.valueOf(value) }.getOrDefault(DebtKind.AADE)
}

@Database(
    entities = [
        OfferEntity::class, SpaceEntity::class, NoteEntity::class,
        NotePresetEntity::class, DebtEntity::class, EmployeeEntity::class,
    ],
    version = 10,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class ProsforaDatabase : RoomDatabase() {

    abstract fun offerDao(): OfferDao
    abstract fun spaceDao(): SpaceDao
    abstract fun noteDao(): NoteDao
    abstract fun notePresetDao(): NotePresetDao
    abstract fun debtDao(): DebtDao
    abstract fun employeeDao(): EmployeeDao

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

        /** v6: από πού ήρθε η προσφορά — κενό για όσες γράφτηκαν στην εφαρμογή. */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL("ALTER TABLE offers ADD COLUMN source TEXT NOT NULL DEFAULT ''")
            }
        }

        /**
         * v7: όνομα και επώνυμο χωριστά, με φύλο για την προσφώνηση.
         *
         * Ό,τι υπήρχε ήταν ένα ενιαίο «Ονοματεπώνυμο». Σπάει στο πρώτο κενό:
         * στα ελληνικά γράφεται σχεδόν πάντα «Μαρία Παπαδοπούλου», οπότε το
         * πρώτο κομμάτι είναι το μικρό όνομα και τα υπόλοιπα το επώνυμο.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL(
                    "ALTER TABLE offers ADD COLUMN customerLastName TEXT NOT NULL DEFAULT ''",
                )
                connection.execSQL(
                    "ALTER TABLE offers ADD COLUMN customerGender TEXT NOT NULL DEFAULT 'UNKNOWN'",
                )
                connection.execSQL(
                    """
                    UPDATE offers SET
                        customerLastName = CASE
                            WHEN instr(customerName, ' ') > 0
                            THEN substr(customerName, instr(customerName, ' ') + 1)
                            ELSE ''
                        END,
                        customerName = CASE
                            WHEN instr(customerName, ' ') > 0
                            THEN substr(customerName, 1, instr(customerName, ' ') - 1)
                            ELSE customerName
                        END
                    """.trimIndent(),
                )
            }
        }

        /**
         * v8: ΦΠΑ στην προσφορά, και ο πίνακας των οφειλών.
         *
         * Ο πίνακας γράφεται με το χέρι και όχι με `fallbackToDestructiveMigration`:
         * μια αποτυχία εδώ πρέπει να ουρλιάξει, όχι να σβήσει τη βάση.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL(
                    "ALTER TABLE offers ADD COLUMN vatIncluded INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS debts (
                        id TEXT NOT NULL PRIMARY KEY,
                        kind TEXT NOT NULL,
                        periodMonth INTEGER NOT NULL,
                        periodYear INTEGER NOT NULL,
                        dueDay INTEGER,
                        amount REAL NOT NULL,
                        reference TEXT NOT NULL,
                        description TEXT NOT NULL,
                        personName TEXT NOT NULL,
                        personCode TEXT NOT NULL,
                        paid INTEGER NOT NULL,
                        paidAt INTEGER,
                        source TEXT NOT NULL,
                        driveFileId TEXT NOT NULL,
                        createdAt INTEGER NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        deleted INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_debts_periodYear_periodMonth " +
                        "ON debts (periodYear, periodMonth)",
                )
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_debts_kind ON debts (kind)")
            }
        }

        /**
         * v9: προαιρετικές χρεώσεις στην προσφορά, ημερομηνία πληρωμής στις
         * οφειλές, και το ευρετήριο εργαζομένων.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL("ALTER TABLE debts ADD COLUMN paidDay INTEGER")
                connection.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS employees (
                        id TEXT NOT NULL PRIMARY KEY,
                        name TEXT NOT NULL,
                        alias TEXT NOT NULL,
                        code TEXT NOT NULL,
                        updatedAt INTEGER NOT NULL,
                        deleted INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                connection.execSQL(
                    "ALTER TABLE offers ADD COLUMN scaffolding INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    "ALTER TABLE offers ADD COLUMN scaffoldingCost REAL NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    "ALTER TABLE offers ADD COLUMN permit INTEGER NOT NULL DEFAULT 0",
                )
                connection.execSQL(
                    "ALTER TABLE offers ADD COLUMN permitCost REAL NOT NULL DEFAULT 0",
                )
            }
        }

        /** v10: ποιος καταχώρησε την οφειλή, για τις ειδοποιήσεις σε κοινή βάση. */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL(
                    "ALTER TABLE debts ADD COLUMN createdBy TEXT NOT NULL DEFAULT ''",
                )
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
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4,
                    MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8,
                    MIGRATION_8_9, MIGRATION_9_10,
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
