package gr.prosfora.app.google

import android.content.Context
import gr.prosfora.app.data.db.DebtAgency
import gr.prosfora.app.data.db.ProsforaDatabase
import gr.prosfora.app.notify.DriveNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Παρακολουθεί τον φάκελο του Drive για ό,τι δεν έκανε η ίδια η εφαρμογή.
 *
 * Ο φάκελος μπορεί να είναι κοινόχρηστος: ένας συνεργάτης ανεβάζει ένα
 * παραστατικό από τον υπολογιστή του και η συσκευή δεν το ξέρει. Εδώ συγκρίνεται
 * το περιεχόμενο των φακέλων με ό,τι έχει ήδη δει η εφαρμογή —
 *
 *  * **νέο αρχείο** → σηματάκι στο μενού και ειδοποίηση με το ποιος το ανέβασε
 *  * **αρχείο που χάθηκε** → οι οφειλές που κρέμονταν από αυτό φεύγουν κι αυτές
 *
 * Ό,τι ανεβάζει η ίδια η συσκευή καταγράφεται αμέσως, οπότε δεν αυτοειδοποιείται.
 * Το ίδιο και ό,τι ανέβασε ο **ίδιος** λογαριασμός από αλλού: η ειδοποίηση θα
 * ήταν θόρυβος.
 */
object DriveWatch {

    /** Πού βρέθηκε η αλλαγή — καθορίζει και πού μπαίνει το σηματάκι. */
    enum class Area(val label: String) {
        DEBTS("Οφειλές"),
        PDF("Αρχείο PDF"),
    }

    data class Change(
        val area: Area,
        val file: DriveClient.DriveFile,
        val removed: Boolean = false,
    ) {
        /** «ανέβηκε από τον/την …» — κενό όταν το Drive δεν το λέει. */
        val author: String
            get() = file.modifiedBy.ifBlank { file.modifiedByEmail }
    }

    private val _changes = MutableStateFlow<List<Change>>(emptyList())
    val changes: StateFlow<List<Change>> = _changes.asStateFlow()

    fun countIn(area: Area): Int = _changes.value.count { it.area == area }

    /** Ο χρήστης τα είδε: το σηματάκι σβήνει και δεν ξαναβγαίνει γι' αυτά. */
    fun acknowledge(context: Context, area: Area) {
        val settings = GoogleSettings(context)
        val (mine, rest) = _changes.value.partition { it.area == area }
        settings.rememberDriveFiles(mine.filterNot { it.removed }.map { it.file.id })
        _changes.value = rest
    }

    /**
     * Κοιτάζει τους φακέλους και επιστρέφει τι άλλαξε.
     *
     * Την **πρώτη** φορά δεν ειδοποιεί για τίποτα: καταγράφει ό,τι υπάρχει ως
     * γνωστό. Αλλιώς η πρώτη εκτέλεση θα φώναζε για κάθε αρχείο που έχει
     * ανεβάσει ο χρήστης εδώ και μήνες.
     */
    suspend fun refresh(
        context: Context,
        drive: DriveClient,
        settings: GoogleSettings,
    ): List<Change> = withContext(Dispatchers.IO) {
        val workspace = DriveWorkspace(drive, settings)
        val seen = LinkedHashMap<String, Pair<Area, DriveClient.DriveFile>>()

        // Μια αποτυχία σε έναν φάκελο δεν πρέπει να μοιάσει με «άδειασε»:
        // κρατάμε ποιοι φάκελοι διαβάστηκαν όντως, και μόνο γι' αυτούς
        // θεωρούμε ότι η απουσία σημαίνει διαγραφή
        var readAll = true

        DebtAgency.entries.forEach { agency ->
            val folder = runCatching { workspace.debtsFolder(agency) }.getOrNull()
            if (folder == null) {
                readAll = false
                return@forEach
            }
            runCatching { workspace.documentsIn(folder) }
                .onFailure { readAll = false }
                .getOrDefault(emptyList())
                .forEach { seen[it.id] = Area.DEBTS to it }
        }

        runCatching { workspace.pdfYears() }
            .onFailure { readAll = false }
            .getOrDefault(emptyList())
            .forEach { year ->
                runCatching { workspace.pdfsInYear(year.id) }
                    .onFailure { readAll = false }
                    .getOrDefault(emptyList())
                    .forEach { seen[it.id] = Area.PDF to it }
            }

        if (!settings.driveWatchReady) {
            // Χωρίς δίκτυο δεν διαβάστηκε τίποτα. Αν σημειωθεί «έτοιμο» τώρα,
            // η καταγραφή μένει άδεια και στο επόμενο άνοιγμα κάθε παραστατικό
            // του περασμένου χρόνου θα μοιάζει νέο — δεκάδες ειδοποιήσεις για
            // αρχεία που ανέβασε ο ίδιος ο χρήστης. Καλύτερα να περιμένει.
            if (!readAll) return@withContext emptyList()

            settings.rememberDriveFiles(seen.keys)
            settings.driveWatchReady = true
            _changes.value = emptyList()
            return@withContext emptyList()
        }

        val known = settings.knownDriveIds
        val mine = settings.ownerEmail.trim().lowercase()

        val added = seen.values
            .filter { (_, file) -> file.id !in known }
            // Ό,τι ανέβασε ο ίδιος λογαριασμός δεν είναι είδηση για τη συσκευή του
            .filterNot { (_, file) ->
                mine.isNotBlank() && file.modifiedByEmail.trim().lowercase() == mine
            }
            .map { (area, file) -> Change(area, file) }

        // Όσα καταγράφηκαν κάποτε αλλά δεν υπάρχουν πια
        val removed = if (!readAll) emptyList() else {
            val gone = known - seen.keys
            forgetRemoved(context, settings, gone)
        }

        // Ό,τι ήρθε από τον ίδιο τον χρήστη μπαίνει σιωπηλά στα γνωστά
        settings.rememberDriveFiles(
            seen.keys - added.map { it.file.id }.toSet(),
        )

        val all = added + removed
        _changes.value = all
        if (all.isNotEmpty() && settings.notifyDriveChanges) {
            DriveNotifier.notify(context, all)
        }
        all
    }

    /**
     * Σβήνει τις οφειλές που κρέμονταν από αρχεία που δεν υπάρχουν πια.
     *
     * Soft delete, όπως παντού: η διαγραφή ταξιδεύει στις άλλες συσκευές μέσω
     * του κοινόχρηστου Sheet αντί να επιστρέψει στον επόμενο συγχρονισμό.
     */
    private suspend fun forgetRemoved(
        context: Context,
        settings: GoogleSettings,
        goneIds: Set<String>,
    ): List<Change> {
        if (goneIds.isEmpty()) return emptyList()
        val dao = ProsforaDatabase.get(context).debtDao()
        val now = System.currentTimeMillis()

        val orphaned = dao.allForSync().filter { !it.deleted && it.driveFileId in goneIds }
        orphaned.forEach { dao.softDelete(it.id, now) }
        settings.forgetDriveFiles(goneIds)

        // Μία ειδοποίηση ανά αρχείο, με το όνομα που είχε στη βάση
        return goneIds.mapNotNull { id ->
            val name = orphaned.firstOrNull { it.driveFileId == id }?.source ?: return@mapNotNull null
            Change(
                area = Area.DEBTS,
                file = DriveClient.DriveFile(id = id, name = name, modifiedTime = null),
                removed = true,
            )
        }
    }
}
