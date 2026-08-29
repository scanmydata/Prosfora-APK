package gr.prosfora.app.sync

import android.content.Context
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.DebtKind
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.data.db.NoteEntity
import gr.prosfora.app.data.db.OfferEntity
import gr.prosfora.app.data.db.OfferStatus
import gr.prosfora.app.data.db.ProsforaDatabase
import gr.prosfora.app.data.db.SpaceEntity
import gr.prosfora.app.debt.DebtRepository
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.SheetsClient
import gr.prosfora.app.notify.DriveNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SheetSync(private val context: Context, private val sheets: SheetsClient, private val settings: GoogleSettings) {
    data class Report(val pulledOffers: Int, val pulledSpaces: Int, val pulledNotes: Int, val pulledDebts: Int, val pushedRows: Int) {
        val summary: String get() = "Λήφθηκαν $pulledOffers προσφορές / $pulledSpaces χώροι / $pulledNotes σημειώσεις / $pulledDebts οφειλές · στάλθηκαν $pushedRows γραμμές"
    }
    private val db = ProsforaDatabase.get(context)

    suspend fun sync(): Report = withContext(Dispatchers.IO) {
        val spreadsheetId = settings.spreadsheetId ?: error("Δεν έχει οριστεί κοινόχρηστο Sheet")
        ensureTabs(spreadsheetId)

        // Repair legacy/fallback employee rows before reading local state. This
        // prevents stale duplicate IDs from being merged back into the shared
        // Sheet and recreating duplicate employee cards on another device.
        DebtRepository(context).repairEmployeeIndex()

        val remoteOffers = readOffers(spreadsheetId)
        val remoteSpaces = readSpaces(spreadsheetId)
        val remoteNotes = readNotes(spreadsheetId)
        val remoteDebts = readDebts(spreadsheetId)
        val remoteEmployees = readEmployees(spreadsheetId)
        val localOffers = db.offerDao().allForSync()
        val localSpaces = db.spaceDao().allForSync()
        val localNotes = db.noteDao().allForSync()
        val localDebts = db.debtDao().allForSync()
        val localEmployees = db.employeeDao().allForSync()
        val mergedOffers = merge(localOffers, remoteOffers, { it.id }, { it.updatedAt })
        val mergedSpaces = merge(localSpaces, remoteSpaces, { it.id }, { it.updatedAt })
        val mergedNotes = merge(localNotes, remoteNotes, { it.id }, { it.updatedAt })
        val mergedDebts = merge(localDebts, remoteDebts, { it.id }, { it.updatedAt })
        val mergedEmployees = merge(localEmployees, remoteEmployees, { it.id }, { it.updatedAt })
        var pulledOffers = 0
        mergedOffers.forEach { merged -> if (localOffers.none { it.id == merged.id && it == merged }) { db.offerDao().upsert(merged); pulledOffers++ } }
        var pulledSpaces = 0
        mergedSpaces.forEach { merged -> if (localSpaces.none { it.id == merged.id && it == merged }) { db.spaceDao().upsert(merged); pulledSpaces++ } }
        var pulledNotes = 0
        mergedNotes.forEach { merged -> if (localNotes.none { it.id == merged.id && it == merged }) { db.noteDao().upsert(merged); pulledNotes++ } }
        var pulledDebts = 0
        mergedDebts.forEach { merged -> if (localDebts.none { it.id == merged.id && it == merged }) { db.debtDao().upsert(merged); pulledDebts++ } }
        announceForeignDebts(localDebts, mergedDebts)
        sheets.replaceRows(spreadsheetId, TAB_OFFERS, offerRows(mergedOffers))
        sheets.replaceRows(spreadsheetId, TAB_SPACES, spaceRows(mergedSpaces))
        sheets.replaceRows(spreadsheetId, TAB_NOTES, noteRows(mergedNotes))
        mergedEmployees.forEach { merged -> if (localEmployees.none { it.id == merged.id && it == merged }) db.employeeDao().upsert(merged) }
        sheets.replaceRows(spreadsheetId, TAB_DEBTS, debtRows(mergedDebts))
        sheets.replaceRows(spreadsheetId, TAB_PEOPLE, employeeRows(mergedEmployees))
        settings.lastSyncAt = System.currentTimeMillis()
        Report(pulledOffers, pulledSpaces, pulledNotes, pulledDebts, mergedOffers.size + mergedSpaces.size + mergedNotes.size + mergedDebts.size + mergedEmployees.size)
    }

    private fun announceForeignDebts(local: List<DebtEntity>, merged: List<DebtEntity>) {
        val mine = settings.ownerEmail.trim().lowercase()
        val known = local.map { it.id }.toSet()
        val fresh = merged.filter { debt -> !debt.deleted && debt.id !in known && debt.createdBy.isNotBlank() && debt.createdBy.trim().lowercase() != mine }
        if (fresh.isNotEmpty()) DriveNotifier.notifyDebts(context, fresh)
    }

    suspend fun createSharedSheet(title: String, drive: gr.prosfora.app.google.DriveClient? = null): String = withContext(Dispatchers.IO) {
        val id = sheets.createSpreadsheet(title, ALL_TABS)
        settings.spreadsheetId = id
        if (drive != null) {
            val folder = gr.prosfora.app.google.DriveWorkspace(drive, settings).rootFolder()
            runCatching { drive.moveToFolder(id, folder) }
        }
        sync(); id
    }

    private suspend fun ensureTabs(spreadsheetId: String) {
        val existing = sheets.sheetTitles(spreadsheetId)
        ALL_TABS.filterNot { it in existing }.forEach { sheets.addSheet(spreadsheetId, it) }
        if (TAB_EMPLOYEE_COSTS !in existing) sheets.replaceRows(spreadsheetId, TAB_EMPLOYEE_COSTS, listOf(EMPLOYEE_COST_HEADER))
        else if (sheets.readRows(spreadsheetId, TAB_EMPLOYEE_COSTS).isEmpty()) sheets.replaceRows(spreadsheetId, TAB_EMPLOYEE_COSTS, listOf(EMPLOYEE_COST_HEADER))
    }

    private fun <T> merge(local: List<T>, remote: List<T>, id: (T) -> String, updatedAt: (T) -> Long): List<T> {
        val byId = LinkedHashMap<String, T>(); local.forEach { byId[id(it)] = it }
        remote.forEach { incoming -> val key = id(incoming); val existing = byId[key]; if (existing == null || updatedAt(incoming) > updatedAt(existing)) byId[key] = incoming }
        return byId.values.toList()
    }

    private suspend fun readOffers(spreadsheetId: String): List<OfferEntity> = dataRows(spreadsheetId, TAB_OFFERS, OFFER_HEADER.size).map { row ->
        OfferEntity(
            id = row[0], address = row[1], dateEpochDay = row[2].toLongOrNull() ?: 0L, kind = row[3], email = row[4],
            status = runCatching { OfferStatus.valueOf(row[5]) }.getOrElse { OfferStatus.fromLabel(row[5]) },
            createdAt = row[6].toLongOrNull() ?: 0L, updatedAt = row[7].toLongOrNull() ?: 0L, lastSentAt = row[8].toLongOrNull(), deleted = row[9] == "1",
            customerName = row[10], customerPhone = row[11], notifiedAt = row[12].toLongOrNull(), notifiedVia = row[13].ifBlank { null },
            workStartDay = row[14].toLongOrNull(), workEndDay = row[15].toLongOrNull(), reviewSentAt = row[16].toLongOrNull(), validUntilDay = row[17].toLongOrNull(),
            paymentTerms = row[18], source = row[19], customerLastName = row[20],
            customerGender = runCatching { gr.prosfora.app.data.db.Gender.valueOf(row[21]) }.getOrDefault(gr.prosfora.app.data.db.Gender.UNKNOWN),
            vatIncluded = row[22] == "1", scaffolding = row[23] == "1", scaffoldingCost = row[24].toDoubleOrNull() ?: 0.0,
            permit = row[25] == "1", permitCost = row[26].toDoubleOrNull() ?: 0.0,
            customExtraName = row.getOrElse(27) { "" }, customExtraCost = row.getOrElse(28) { "" }.toDoubleOrNull() ?: 0.0,
        )
    }

    private suspend fun readDebts(spreadsheetId: String): List<DebtEntity> = dataRows(spreadsheetId, TAB_DEBTS, DEBT_HEADER.size).map { row ->
        DebtEntity(
            id = row[0], kind = runCatching { DebtKind.valueOf(row[1]) }.getOrDefault(DebtKind.AADE), periodMonth = row[2].toIntOrNull() ?: 0, periodYear = row[3].toIntOrNull() ?: 0,
            dueDay = row[4].toLongOrNull(), amount = row[5].toDoubleOrNull() ?: 0.0, reference = row[6], description = row[7], personName = row[8], personCode = row[9],
            paid = row[10] == "1", paidAt = row[11].toLongOrNull(), paidDay = row[17].toLongOrNull(), createdBy = row[18], source = row[12], driveFileId = row[13],
            createdAt = row[14].toLongOrNull() ?: 0L, updatedAt = row[15].toLongOrNull() ?: 0L, deleted = row[16] == "1",
            amIka = row.getOrElse(19) { "" },
        )
    }

    private suspend fun readEmployees(spreadsheetId: String): List<EmployeeEntity> = dataRows(spreadsheetId, TAB_PEOPLE, PEOPLE_HEADER.size)
        .mapNotNull { row ->
            val amIka = EmployeeEntity.normalizeIka(row.getOrElse(7) { "" })
            if (amIka.isBlank()) null else EmployeeEntity(
                id = EmployeeEntity.idForAmIka(amIka),
                amIka = amIka,
                name = row[1],
                alias = row[2],
                code = row[3],
                updatedAt = row[4].toLongOrNull() ?: 0L,
                deleted = row[5] == "1",
                leftDay = row[6].toLongOrNull(),
            )
        }

    private suspend fun readSpaces(spreadsheetId: String): List<SpaceEntity> = dataRows(spreadsheetId, TAB_SPACES, SPACE_HEADER.size).map { row -> SpaceEntity(id = row[0], offerId = row[1], description = row[2], area = row[3].toDoubleOrNull() ?: 0.0, unitPrice = row[4].toDoubleOrNull() ?: 0.0, position = row[5].toIntOrNull() ?: 0, updatedAt = row[6].toLongOrNull() ?: 0L, deleted = row[7] == "1") }
    private suspend fun readNotes(spreadsheetId: String,): List<NoteEntity> = dataRows(spreadsheetId, TAB_NOTES, NOTE_HEADER.size).map { row -> NoteEntity(id = row[0], offerId = row[1], text = row[2], position = row[3].toIntOrNull() ?: 0, updatedAt = row[4].toLongOrNull() ?: 0L, deleted = row[5] == "1") }

    private suspend fun dataRows(spreadsheetId: String, tab: String, width: Int): List<List<String>> {
        val rows = sheets.readRows(spreadsheetId, tab)
        if (rows.isEmpty()) return emptyList()
        return rows.drop(1).filter { it.firstOrNull()?.isNotBlank() == true }.map { row -> List(width) { i -> row.getOrElse(i) { "" } } }
    }

    private fun offerRows(offers: List<OfferEntity>) = listOf(OFFER_HEADER) + offers.map {
        listOf(it.id, it.address, it.dateEpochDay.toString(), it.kind, it.email, it.status.name, it.createdAt.toString(), it.updatedAt.toString(), it.lastSentAt?.toString().orEmpty(), if (it.deleted) "1" else "0", it.customerName, it.customerPhone, it.notifiedAt?.toString().orEmpty(), it.notifiedVia.orEmpty(), it.workStartDay?.toString().orEmpty(), it.workEndDay?.toString().orEmpty(), it.reviewSentAt?.toString().orEmpty(), it.validUntilDay?.toString().orEmpty(), it.paymentTerms, it.source, it.customerLastName, it.customerGender.name, if (it.vatIncluded) "1" else "0", if (it.scaffolding) "1" else "0", it.scaffoldingCost.toString(), if (it.permit) "1" else "0", it.permitCost.toString(), it.customExtraName, it.customExtraCost.toString())
    }
    private fun debtRows(debts: List<DebtEntity>) = listOf(DEBT_HEADER) + debts.map { listOf(it.id, it.kind.name, it.periodMonth.toString(), it.periodYear.toString(), it.dueDay?.toString().orEmpty(), it.amount.toString(), it.reference, it.description, it.personName, it.personCode, if (it.paid) "1" else "0", it.paidAt?.toString().orEmpty(), it.source, it.driveFileId, it.createdAt.toString(), it.updatedAt.toString(), if (it.deleted) "1" else "0", it.paidDay?.toString().orEmpty(), it.createdBy, it.amIka) }
    private fun employeeRows(people: List<EmployeeEntity>) = listOf(PEOPLE_HEADER) + people.map { listOf(it.id, it.name, it.alias, it.code, it.updatedAt.toString(), if (it.deleted) "1" else "0", it.leftDay?.toString().orEmpty(), it.amIka) }
    private fun spaceRows(spaces: List<SpaceEntity>) = listOf(SPACE_HEADER) + spaces.map { listOf(it.id, it.offerId, it.description, it.area.toString(), it.unitPrice.toString(), it.position.toString(), it.updatedAt.toString(), if (it.deleted) "1" else "0") }
    private fun noteRows(notes: List<NoteEntity>) = listOf(NOTE_HEADER) + notes.map { listOf(it.id, it.offerId, it.text, it.position.toString(), it.updatedAt.toString(), if (it.deleted) "1" else "0") }

    companion object {
        const val TAB_OFFERS = "Προσφορές"
        const val TAB_SPACES = "Χώροι_έργου"
        const val TAB_NOTES = "Λίστα_Παρατηρήσεων"
        const val TAB_DEBTS = "Οφειλές"
        const val TAB_PEOPLE = "Εργαζόμενοι"
        const val TAB_EMPLOYEE_COSTS = "Κόστη_Εργαζομένων"
        val ALL_TABS = listOf(TAB_OFFERS, TAB_SPACES, TAB_NOTES, TAB_DEBTS, TAB_PEOPLE, TAB_EMPLOYEE_COSTS)
        val EMPLOYEE_COST_HEADER = listOf("ID_Εργαζομένου", "Όνομα", "Έτος", "Μήνας", "Πληρωτέο", "Κόστος ενσήμων", "Αρχείο Drive", "Ενημερώθηκε")
        private val OFFER_HEADER = listOf("ID_Προσφοράς", "Οδός / Περιοχή", "Ημερομηνία", "Είδος", "Email", "Κατάσταση", "Δημιουργήθηκε", "Ενημερώθηκε", "Στάλθηκε", "Διαγραμμένο", "Ονοματεπώνυμο", "Κινητό", "Ειδοποιήθηκε", "Μέσο ειδοποίησης", "Έναρξη εργασιών", "Ολοκλήρωση εργασιών", "Αξιολόγηση", "Ισχύει έως", "Τρόπος πληρωμής", "Πηγή", "Επώνυμο", "Φύλο", "ΦΠΑ", "Σκαλωσιά", "Κόστος σκαλωσιάς", "Άδεια", "Κόστος άδειας", "Πρόσθετο κόστος", "Τιμή πρόσθετου κόστους")
        private val DEBT_HEADER = listOf("ID_Οφειλής", "Φορέας", "Μήνα", "Έτος", "Λήξη", "Ποσό", "Ταυτότητα / RF", "Περιγραφή", "Εργαζόμενος", "Κωδικός", "Πληρώθηκε", "Ημ. πληρωμής", "Πηγή", "Αρχείο Drive", "Δημιουργήθηκε", "Ενημερώθηκε", "Διαγραμμένο", "Ημ. εξόφλησης", "Καταχωρήθηκε από", "ΑΜ ΙΚΑ")
        private val PEOPLE_HEADER = listOf("ID_Εργαζόμενου", "Όνομα", "Ψευδώνυμο", "Κωδικός", "Ενημερώθηκε", "Διαγραμμένο", "Αποχώρηση", "ΑΜ ΙΚΑ")
        private val SPACE_HEADER = listOf("ID_Χώρου", "ID_Προσφοράς", "Περιγραφή Χώρου", "Επιφάνεια (τ.μ.)", "Τιμή Μονάδος", "Σειρά", "Ενημερώθηκε", "Διαγραμμένο")
        private val NOTE_HEADER = listOf("ID_Παρατήρησης", "ID_Προσφοράς", "Κείμενο", "Σειρά", "Ενημερώθηκε", "Διαγραμμένο")
    }
}