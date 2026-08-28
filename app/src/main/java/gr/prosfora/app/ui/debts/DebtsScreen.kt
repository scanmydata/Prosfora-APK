package gr.prosfora.app.ui.debts

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gr.prosfora.app.data.db.DebtAgency
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.debt.DebtImporter
import gr.prosfora.app.debt.DebtRepository
import gr.prosfora.app.google.DriveClient
import gr.prosfora.app.google.DriveWatch
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.ui.MenuButton
import gr.prosfora.app.ui.components.ConfirmDialog
import gr.prosfora.app.ui.offers.DeleteRed
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.asOfferDate
import gr.prosfora.app.util.reason
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Οι οφειλές της επιχείρησης, χωρισμένες ανά έτος, ανά φορέα και ανά μήνα.
 *
 * Δύο δρόμοι για να μπουν: με το χέρι από το «+», ή διαβασμένες από τα
 * παραστατικά που κάθονται στον φάκελο «Οφειλές» του Drive. Ό,τι διαβάζεται
 * περνάει πρώτα από επιβεβαίωση — μια λάθος ανάγνωση δεν πρέπει να μπει σιωπηλά
 * στη βάση.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebtsScreen(onMenu: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = remember { DebtRepository(context) }
    val settings = remember { GoogleSettings(context) }
    val authorizer = rememberGoogleAuthorizer()

    val stream = remember(repository) { repository.observeAll() }
    val debts by stream.collectAsState(initial = emptyList())
    val peopleStream = remember(repository) { repository.observeEmployees() }
    val people by peopleStream.collectAsState(initial = emptyList())
    // Τα ψευδώνυμα αντικαθιστούν το τυπωμένο όνομα παντού στη λίστα
    val aliases = remember(people) {
        people.filter { it.alias.isNotBlank() }.associate { it.id to it.alias }
    }
    val changes by DriveWatch.changes.collectAsState()
    val newInDrive = remember(changes) { changes.filter { it.area == DriveWatch.Area.DEBTS } }

    var agency by remember { mutableStateOf<DebtAgency?>(null) }
    var onlyUnpaid by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<DebtEntity?>(null) }
    var pending by remember { mutableStateOf<DebtImporter.Report?>(null) }
    var afmRejected by remember { mutableStateOf<DebtImporter.Found?>(null) }
    var afmRejectedQueue by remember { mutableStateOf(emptyList<DebtImporter.Found>()) }
    var pendingAfterAfm by remember { mutableStateOf<DebtImporter.Report?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var showPeople by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var confirmBulk by remember { mutableStateOf(false) }
    var payingFor by remember { mutableStateOf<DebtEntity?>(null) }
    val years = remember(debts) {
        debts.map { it.periodYear }.filter { it > 0 }.distinct().sortedDescending()
            .ifEmpty { listOf(LocalDate.now().year) }
    }
    var year by remember(years) { mutableStateOf(years.first()) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    fun advanceAfmReview() {
        val remaining = afmRejectedQueue.drop(1)
        afmRejectedQueue = remaining
        if (remaining.isNotEmpty()) {
            afmRejected = remaining.first()
        } else {
            val report = pendingAfterAfm
            pendingAfterAfm = null
            afmRejected = null
            if (report != null) {
                if (report.debts.isEmpty()) toast(report.summary) else pending = report
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Ανέβασμα και ανάγνωση…"
            val result = runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Δεν διαβάστηκε το αρχείο")
                val name = displayName(context, uri)
                val drive = DriveClient(authorizer.accessToken())
                // Δεν ρωτάμε τι είναι: το κείμενο του παραστατικού το λέει,
                // και το αρχείο μετακινείται μόνο του στον σωστό φάκελο
                DebtImporter(drive, settings).importFile(name, bytes)
            }
            busy = null
            result.onSuccess { found ->
                if (found.afmMismatch) {
                    afmRejectedQueue = listOf(found)
                    afmRejected = found
                    pendingAfterAfm = null
                } else {
                    pending = DebtImporter.Report(scanned = 1, skipped = 0, found = listOf(found))
                }
            }.onFailure { toast("Δεν έγινε η εισαγωγή: ${it.reason()}") }
        }
    }

    fun scanDrive() {
        scope.launch {
            busy = "Αναζήτηση στο Drive…"
            val result = runCatching {
                val drive = DriveClient(authorizer.accessToken())
                DebtImporter(drive, settings)
                    .scan(repository.importedFileIds()) { name -> busy = "Ανάγνωση $name…" }
            }
            busy = null
            DriveWatch.acknowledge(context, DriveWatch.Area.DEBTS)
            result.onSuccess { report ->
                val mismatches = report.found.filter { it.afmMismatch }
                val valid = report.found.filterNot { it.afmMismatch }
                if (mismatches.isNotEmpty()) {
                    afmRejectedQueue = mismatches
                    afmRejected = mismatches.first()
                    pendingAfterAfm = report.copy(found = valid)
                } else if (report.debts.isEmpty()) {
                    toast(report.summary)
                } else {
                    pending = report
                }
            }.onFailure { toast("Η σάρωση απέτυχε: ${it.reason()}") }
        }
    }

    fun openFolder() {
        scope.launch {
            val url = runCatching {
                DebtImporter(DriveClient(authorizer.accessToken()), settings).folderUrl()
            }.getOrNull() ?: return@launch
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    fun togglePaid(debt: DebtEntity) {
        if (!debt.paid && settings.askPaidDate) {
            payingFor = debt
        } else {
            scope.launch { repository.setPaid(debt.id, !debt.paid) }
        }
    }

    val visible = remember(debts, agency, onlyUnpaid, year) {
        debts.filter {
            inYear(it, year) &&
                (agency == null || it.agency == agency) &&
                (!onlyUnpaid || !it.paid)
        }
    }
    // Νεότερος μήνας πρώτος
    val groups = remember(visible) {
        visible.groupBy { it.periodKey }.entries.sortedByDescending { it.key }
    }

    Scaffold(
        topBar = {
            if (selected.isEmpty()) {
                TopAppBar(
                    title = { Text("Οφειλές") },
                    navigationIcon = { MenuButton(onMenu) },
                    actions = {
                        IconButton(onClick = { showPeople = true }) {
                            Icon(Icons.Default.Groups, contentDescription = "Εργαζόμενοι")
                        }
                        IconButton(onClick = { openFolder() }) {
                            Icon(Icons.Default.FolderOpen, contentDescription = "Φάκελος στο Drive")
                        }
                        IconButton(onClick = { picker.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Default.UploadFile, contentDescription = "Εισαγωγή αρχείου")
                        }
                        IconButton(onClick = { scanDrive() }) {
                            Icon(Icons.Default.CloudSync, contentDescription = "Σάρωση Drive")
                        }
                    },
                )
            } else {
                // Μπάρα επιλογής: αντικαθιστά την κανονική όσο υπάρχουν επιλεγμένες
                TopAppBar(
                    title = { Text("${selected.size} επιλεγμένες") },
                    navigationIcon = {
                        IconButton(onClick = { selected = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = "Ακύρωση επιλογής")
                        }
                    },
                    actions = {
                        IconButton(onClick = { confirmBulk = true }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Διαγραφή",
                                tint = DeleteRed,
                            )
                        }
                    },
                )
            }
        },
        floatingActionButton = {
            if (selected.isEmpty()) {
                FloatingActionButton(onClick = { editing = DebtEntity() }) {
                    Icon(Icons.Default.Add, contentDescription = "Νέα οφειλή")
                }
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (newInDrive.isNotEmpty()) {
                item {
                    DriveChangesCard(
                        changes = newInDrive,
                        onRead = { scanDrive() },
                        onDismiss = { DriveWatch.acknowledge(context, DriveWatch.Area.DEBTS) },
                    )
                }
            }

            item { TotalsCard(debts.filter { inYear(it, year) }) }

            item {
                // Ιστορικότητα: ένα έτος τη φορά, νεότερο πρώτα
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(years) { candidate ->
                        FilterChip(
                            selected = year == candidate,
                            onClick = { year = candidate },
                            label = { Text(candidate.toString(), maxLines = 1) },
                        )
                    }
                }
            }

            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        FilterChip(
                            selected = agency == null,
                            onClick = { agency = null },
                            label = { Text("Όλες", maxLines = 1) },
                        )
                    }
                    items(DebtAgency.entries) { candidate ->
                        val count = debts.count {
                            it.agency == candidate && inYear(it, year) && !it.paid
                        }
                        FilterChip(
                            selected = agency == candidate,
                            onClick = { agency = if (agency == candidate) null else candidate },
                            label = {
                                Text(
                                    if (count > 0) "${candidate.label} · $count" else candidate.label,
                                    maxLines = 1,
                                )
                            },
                        )
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Μόνο απλήρωτες", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = onlyUnpaid, onCheckedChange = { onlyUnpaid = it })
                }
            }

            busy?.let { message ->
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text(
                            message,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }

            if (visible.isEmpty() && busy == null) {
                item { EmptyHint(hasAny = debts.isNotEmpty()) }
            }

            groups.forEach { entry ->
                val rows = entry.value
                val first = rows.first()
                item(key = "head-${first.periodKey}") {
                    PeriodHeader(
                        label = monthLabel(first.periodMonth, first.periodYear),
                        total = rows.sumOf { it.amount },
                    )
                }
                items(rows.sortedBy { it.kind.ordinal }, key = { it.id }) { debt ->
                    DebtRow(
                        debt = debt,
                        title = titleFor(debt, aliases),
                        selected = debt.id in selected,
                        selecting = selected.isNotEmpty(),
                        onToggle = { togglePaid(debt) },
                        onOpen = { editing = debt },
                        onSelect = {
                            selected = if (debt.id in selected) {
                                selected - debt.id
                            } else {
                                selected + debt.id
                            }
                        },
                        onCopy = { copyToClipboard(context, it, debt.title) },
                    )
                }
            }
        }
    }

    if (confirmBulk) {
        val victims = selected
        ConfirmDialog(
            title = "Διαγραφή ${victims.size} οφειλών",
            message = "Θα σβηστούν από τη βάση και από τις άλλες συσκευές.",
            confirmLabel = "Διαγραφή",
            onConfirm = {
                confirmBulk = false
                selected = emptySet()
                scope.launch { repository.delete(victims) }
            },
            onDismiss = { confirmBulk = false },
        )
    }

    if (showPeople) {
        EmployeeIndexDialog(
            repository = repository,
            debts = debts,
            onDismiss = { showPeople = false },
        )
    }

    payingFor?.let { debt ->
        PaidDatePicker(
            onDismiss = { payingFor = null },
            onPick = { day ->
                payingFor = null
                scope.launch { repository.setPaid(debt.id, paid = true, day = day) }
            },
        )
    }

    editing?.let { debt ->
        DebtEditorDialog(
            debt = debt,
            onDismiss = { editing = null },
            onSave = {
                editing = null
                scope.launch { repository.save(it) }
            },
            onDelete = {
                editing = null
                scope.launch { repository.delete(debt.id) }
            },
            onDeleteFile = {
                editing = null
                scope.launch {
                    val removed = repository.deleteFromFile(debt.source, debt.driveFileId)
                    toast("Διαγράφηκαν $removed οφειλές από «${debt.source}»")
                }
            },
            onCopy = { copyToClipboard(context, it, debt.title) },
        )
    }


    afmRejected?.let { found ->
        AlertDialog(
            onDismissRequest = { advanceAfmReview() },
            title = { Text("Το αρχείο αφορά άλλο ΑΦΜ") },
            text = {
                Text(
                    "Το «${found.fileName}» δεν αφορά το ΑΦΜ 802576637. " +
                        "Θέλεις να διαγραφεί από το Google Drive;",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val fileId = found.driveFileId
                    val fileName = found.fileName
                    scope.launch {
                        runCatching { DriveClient(authorizer.accessToken()).delete(fileId) }
                            .onSuccess { toast("Το «$fileName» διαγράφηκε από το Drive.") }
                            .onFailure { toast("Δεν ήταν δυνατή η διαγραφή από το Drive: ${it.reason()}") }
                        advanceAfmReview()
                    }
                }) { Text("Διαγραφή από Drive", color = DeleteRed) }
            },
            dismissButton = {
                TextButton(onClick = { advanceAfmReview() }) { Text("Να παραμείνει") }
            },
        )
    }

    pending?.let { report ->
        DebtImportDialog(
            report = report,
            onDismiss = { pending = null },
            onConfirm = { chosen ->
                pending = null
                scope.launch {
                    repository.saveAll(chosen)
                    toast("Αποθηκεύτηκαν ${chosen.size} οφειλές")
                }
            },
        )
    }
}

/**
 * Ανήκει η οφειλή στο έτος που δείχνει η λίστα;
 *
 * Ό,τι δεν έχει έτος φαίνεται **παντού**. Ένα παραστατικό που δεν έδωσε
 * περίοδο αποθηκευόταν κανονικά αλλά δεν ταίριαζε με κανένα φίλτρο, οπότε
 * έμοιαζε σαν να μην είχε γίνει η αποθήκευση. Μια οφειλή που δεν ξέρουμε πότε
 * αφορά είναι ακριβώς αυτή που πρέπει να δει ο χρήστης, όχι να κρυφτεί.
 */
private fun inYear(debt: DebtEntity, year: Int): Boolean =
    debt.periodYear == year || debt.periodYear <= 0

/** Τι εμφανίστηκε στον κοινόχρηστο φάκελο χωρίς να το βάλει αυτή η συσκευή. */
@Composable
private fun DriveChangesCard(
    changes: List<DriveWatch.Change>,
    onRead: () -> Unit,
    onDismiss: () -> Unit,
) {
    val unread = changes.count { !it.removed }
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (changes.size == 1) {
                        "Νέο αρχείο στο Drive"
                    } else {
                        "${changes.size} αλλαγές στο Drive"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                // Χωρίς αυτό μια κάρτα που λέει μόνο «διαγράφηκε» δεν είχε
                // κανένα κουμπί, και έμενε στην οθόνη για πάντα
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Το είδα")
                }
            }
            changes.take(5).forEach { change ->
                val who = change.author.ifBlank { "άγνωστος χρήστης" }
                Text(
                    if (change.removed) {
                        "Διαγράφηκε: ${change.file.name}"
                    } else {
                        "${change.file.name} — από $who"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (unread > 0) {
                TextButton(onClick = onRead) { Text("Διάβασέ τα") }
            }
        }
    }
}

@Composable
private fun TotalsCard(debts: List<DebtEntity>) {
    val today = LocalDate.now()
    val unpaid = debts.filterNot { it.paid }
    val overdue = unpaid.filter { it.overdue(today) }
    // «Σύντομα» = μέσα στις επόμενες δέκα μέρες· τόσο χρειάζεται μια εντολή
    val soon = unpaid.filter { !it.overdue(today) && (it.daysLeft(today) ?: 99L) <= 10 }

    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Απλήρωτες", style = MaterialTheme.typography.labelMedium)
            Text(
                unpaid.sumOf { it.amount }.asMoney(),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (overdue.isNotEmpty()) {
                Text(
                    "Ληξιπρόθεσμες: ${overdue.size} · ${overdue.sumOf { it.amount }.asMoney()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DeleteRed,
                )
            }
            if (soon.isNotEmpty()) {
                Text(
                    "Λήγουν μέσα σε 10 μέρες: ${soon.size} · ${soon.sumOf { it.amount }.asMoney()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PeriodHeader(label: String, total: Double) {
    Column {
        HorizontalDivider(Modifier.padding(bottom = 8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(total.asMoney(), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun DebtRow(
    debt: DebtEntity,
    title: String,
    selected: Boolean,
    selecting: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onSelect: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val today = LocalDate.now()
    Card(
        // Παρατεταμένο πάτημα ανοίγει την επιλογή· από εκεί και πέρα το απλό
        // πάτημα επιλέγει αντί να ανοίγει τη φόρμα
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = { if (selecting) onSelect() else onOpen() },
            onLongClick = onSelect,
        ),
        colors = CardDefaults.cardColors(
            if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            },
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .padding(end = 12.dp)
                    .size(10.dp)
                    .background(debt.kind.color, CircleShape),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitleFor(debt, today),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (debt.overdue(today)) {
                        DeleteRed
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    debt.amount.asMoney(),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                )
                // Η ταυτότητα οφειλής και το RF πάνε στην τράπεζα — ένα άγγιγμα
                // αντί για αντιγραφή με το δάχτυλο από τριάντα ψηφία
                if (debt.reference.isNotBlank()) {
                    TextButton(
                        onClick = { onCopy(debt.reference) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                        )
                        Text("  κωδικός", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Checkbox(checked = debt.paid, onCheckedChange = { onToggle() })
        }
    }
}

/** Ο τίτλος της γραμμής, με το ψευδώνυμο του εργαζόμενου αν έχει οριστεί. */
private fun titleFor(debt: DebtEntity, aliases: Map<String, String>): String {
    if (debt.personName.isBlank()) return debt.title
    return aliases[EmployeeEntity.idFor(debt.personName)] ?: debt.title
}

private fun subtitleFor(debt: DebtEntity, today: LocalDate): String = buildString {
    append(debt.kind.label)
    append(" · ")
    append(debt.periodLabel)
    if (debt.paid) {
        debt.paidDay?.let {
            append(" · πληρώθηκε ")
            append(it.asOfferDate())
        }
        return@buildString
    }
    debt.dueDay?.let { due ->
        append(" · λήξη ")
        append(due.asOfferDate())
        val left = debt.daysLeft(today)
        if (left != null && left < 0) append(" (πέρασε)")
    }
}

@Composable
private fun EmptyHint(hasAny: Boolean) {
    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (hasAny) "Καμία οφειλή με αυτά τα φίλτρα" else "Καμία οφειλή ακόμη",
                style = MaterialTheme.typography.titleSmall,
            )
            if (!hasAny) {
                Text(
                    "Πρόσθεσέ τες με το «+», ή ρίξε τα παραστατικά στον φάκελο " +
                        "«Οφειλές» του Drive και πάτησε τη σάρωση.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Πότε πληρώθηκε στ' αλήθεια — συχνά όχι τη μέρα που τσεκάρεται το κουτάκι. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaidDatePicker(onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = LocalDate.now().toEpochDay() * 86_400_000L,
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                // Ο DatePicker δουλεύει σε UTC· η μετατροπή γίνεται εκεί,
                // αλλιώς η τοπική ζώνη μετακινεί τη μέρα κατά μία
                val day = state.selectedDateMillis?.let { millis ->
                    java.time.Instant.ofEpochMilli(millis)
                        .atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                } ?: LocalDate.now().toEpochDay()
                onPick(day)
            }) { Text("Πληρώθηκε") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Άκυρο") } },
    ) {
        DatePicker(state = state)
    }
}

internal fun copyToClipboard(context: Context, value: String, label: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "Αντιγράφηκε: $value", Toast.LENGTH_SHORT).show()
}

/** Το όνομα του αρχείου όπως το δείχνει ο επιλογέας, για να ταξιδέψει στο Drive. */
private fun displayName(context: Context, uri: Uri): String {
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    cursor?.use {
        val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && it.moveToFirst()) {
            val name = it.getString(index)
            if (!name.isNullOrBlank()) return name
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/') ?: "παραστατικό.pdf"
}
