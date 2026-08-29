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
    @TypeConverter fun statusToString(status: OfferStatus): String = status.name
    @TypeConverter fun stringToStatus(value: String): OfferStatus = runCatching { OfferStatus.valueOf(value) }.getOrDefault(OfferStatus.CREATED)
    @TypeConverter fun genderToString(gender: Gender): String = gender.name
    @TypeConverter fun stringToGender(value: String): Gender = runCatching { Gender.valueOf(value) }.getOrDefault(Gender.UNKNOWN)
    @TypeConverter fun debtKindToString(kind: DebtKind): String = kind.name
    @TypeConverter fun stringToDebtKind(value: String): DebtKind = runCatching { DebtKind.valueOf(value) }.getOrDefault(DebtKind.AADE)
}

@Database(
    entities = [OfferEntity::class, SpaceEntity::class, NoteEntity::class, NotePresetEntity::class, DebtEntity::class, EmployeeEntity::class],
    version = 15,
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
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL("ALTER TABLE offers ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE spaces ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE spaces ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE notes ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE notes ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL("ALTER TABLE offers ADD COLUMN customerName TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE offers ADD COLUMN customerPhone TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE offers ADD COLUMN notifiedAt INTEGER")
                connection.execSQL("ALTER TABLE offers ADD COLUMN notifiedVia TEXT")
            }
        }
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL("ALTER TABLE offers ADD COLUMN workStartDay INTEGER")
                connection.execSQL("ALTER TABLE offers ADD COLUMN workEndDay INTEGER")
                connection.execSQL("ALTER TABLE offers ADD COLUMN reviewSentAt INTEGER")
            }
        }
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL("ALTER TABLE offers ADD COLUMN validUntilDay INTEGER")
                connection.execSQL("ALTER TABLE offers ADD COLUMN paymentTerms TEXT NOT NULL DEFAULT ''")
            }
        }
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SupportSQLiteDatabase) { connection.execSQL("ALTER TABLE offers ADD COLUMN source TEXT NOT NULL DEFAULT ''") }
        }
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL("ALTER TABLE offers ADD COLUMN customerLastName TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE offers ADD COLUMN customerGender TEXT NOT NULL DEFAULT 'UNKNOWN'")
                connection.execSQL("""UPDATE offers SET customerLastName = CASE WHEN instr(customerName, ' ') > 0 THEN substr(customerName, instr(customerName, ' ') + 1) ELSE '' END, customerName = CASE WHEN instr(customerName, ' ') > 0 THEN substr(customerName, 1, instr(customerName, ' ') - 1) ELSE customerName END""")
            }
        }
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL("ALTER TABLE offers ADD COLUMN vatIncluded INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("""CREATE TABLE IF NOT EXISTS debts (id TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, periodMonth INTEGER NOT NULL, periodYear INTEGER NOT NULL, dueDay INTEGER, amount REAL NOT NULL, reference TEXT NOT NULL, description TEXT NOT NULL, personName TEXT NOT NULL, personCode TEXT NOT NULL, paid INTEGER NOT NULL, paidAt INTEGER, source TEXT NOT NULL, driveFileId TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL)""")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_debts_periodYear_periodMonth ON debts (periodYear, periodMonth)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_debts_kind ON debts (kind)")
            }
        }
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL("ALTER TABLE debts ADD COLUMN paidDay INTEGER")
                connection.execSQL("""CREATE TABLE IF NOT EXISTS employees (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL, alias TEXT NOT NULL, code TEXT NOT NULL, updatedAt INTEGER NOT NULL, deleted INTEGER NOT NULL)""")
                connection.execSQL("ALTER TABLE offers ADD COLUMN scaffolding INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE offers ADD COLUMN scaffoldingCost REAL NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE offers ADD COLUMN permit INTEGER NOT NULL DEFAULT 0")
                connection.execSQL("ALTER TABLE offers ADD COLUMN permitCost REAL NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(connection: SupportSQLiteDatabase) { connection.execSQL("ALTER TABLE debts ADD COLUMN createdBy TEXT NOT NULL DEFAULT ''") }
        }
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(connection: SupportSQLiteDatabase) { connection.execSQL("ALTER TABLE employees ADD COLUMN leftDay INTEGER") }
        }
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL("ALTER TABLE offers ADD COLUMN customExtraName TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE offers ADD COLUMN customExtraCost REAL NOT NULL DEFAULT 0")
            }
        }
        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL("ALTER TABLE debts ADD COLUMN amIka TEXT NOT NULL DEFAULT ''")
                connection.execSQL("ALTER TABLE employees ADD COLUMN amIka TEXT NOT NULL DEFAULT ''")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_debts_amIka ON debts(amIka)")
                connection.execSQL("CREATE INDEX IF NOT EXISTS index_employees_amIka ON employees(amIka)")
            }
        }
        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL(
                    """
                    UPDATE debts
                    SET periodMonth = CAST(strftime('%m', dueDay * 86400, 'unixepoch') AS INTEGER),
                        periodYear = CAST(strftime('%Y', dueDay * 86400, 'unixepoch') AS INTEGER)
                    WHERE dueDay IS NOT NULL
                    """.trimIndent(),
                )
            }
        }
        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(connection: SupportSQLiteDatabase) {
                connection.execSQL("ALTER TABLE employees ADD COLUMN payrollSummaryJson TEXT NOT NULL DEFAULT '{}'")
            }
        }

        val DEFAULT_PRESETS = listOf(
            "Στην προσφορά δεν περιλαμβάνεται ο ΦΠΑ τιμολογίου.",
            "Η προσφορά περιλαμβάνει την εργασία και τα υλικά.",
            "Τα χρώματα που θα χρησιμοποιηθούν εσωτερικά θα είναι άοσμα πλαστικά της vivechrom.",
            "Τα δάπεδα θα καλυφθούν με χαρτόνι και τα σοβατεπιά με χαρτοταινία.",
        )

        @Volatile private var instance: ProsforaDatabase? = null
        fun get(context: Context): ProsforaDatabase = instance ?: synchronized(this) { instance ?: build(context).also { instance = it } }

        private fun build(context: Context): ProsforaDatabase {
            lateinit var db: ProsforaDatabase
            db = Room.databaseBuilder(context.applicationContext, ProsforaDatabase::class.java, "prosfora.db")
                .addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5,
                    MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9,
                    MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13,
                    MIGRATION_13_14, MIGRATION_14_15,
                )
                .addCallback(object : Callback() {
                    override fun onCreate(connection: SupportSQLiteDatabase) {
                        super.onCreate(connection)
                        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                            db.notePresetDao().insertAll(DEFAULT_PRESETS.mapIndexed { i, text -> NotePresetEntity(text = text, position = i) })
                        }
                    }
                }).build()
            return db
        }
    }
}
