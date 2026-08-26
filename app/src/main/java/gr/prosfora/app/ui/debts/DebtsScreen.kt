package gr.prosfora.app.ui.debts

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.TopAppBar
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
import gr.prosfora.app.debt.DebtImporter
import gr.prosfora.app.debt.DebtRepository
import gr.prosfora.app.google.DriveClient
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.ui.MenuButton
import gr.prosfora.app.ui.offers.DeleteRed
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.asOfferDate
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Οι οφειλές της επιχείρησης, χωρισμένες ανά φορέα και ανά μήνα αναφοράς.
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

    var agency by remember { mutableStateOf<DebtAgency?>(null) }
    var onlyUnpaid by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf<DebtEntity?>(null) }
    var pending by remember { mutableStateOf<DebtImporter.Report?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    // Ο φορέας διαλέγεται πριν το αρχείο: αυτός ορίζει τον φάκελο του Drive
    var pickingFor by remember { mutableStateOf<DebtAgency?>(null) }
    var askAgency by remember { mutableStateOf(false) }

    fun toast(message: String) = Toast.makeText(context, message, Toast.LENGTH_LONG).show()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val target = pickingFor
        pickingFor = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = "Ανέβασμα και ανάγνωση…"
            val result = runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Δεν διαβάστηκε το αρχείο")
                // Τα PDF αρχίζουν με «%PDF» — ο επιλογέας δείχνει και ό,τι άλλο
                require(bytes.size > 4 && bytes.decodeToString(0, 4) == "%PDF") {
                    "Το αρχείο δεν είναι PDF"
                }
                val name = displayName(context, uri)
                val drive = DriveClient(authorizer.accessToken())
                DebtImporter(drive, settings).importFile(target, name, bytes)
            }
            busy = null
            result.onSuccess { found ->
                pending = DebtImporter.Report(scanned = 1, skipped = 0, found = listOf(found))
            }.onFailure { toast("Δεν έγινε η εισαγωγή: ${it.message}") }
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
            result.onSuccess { report ->
                if (report.debts.isEmpty()) toast(report.summary) else pending = report
            }.onFailure { toast("Η σάρωση απέτυχε: ${it.message}") }
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

    val visible = remember(debts, agency, onlyUnpaid) {
        debts.filter { (agency == null || it.agency == agency) && (!onlyUnpaid || !it.paid) }
    }
    // Νεότερος μήνας πρώτος· οι οφειλές χωρίς περίοδο πέφτουν στο τέλος
    val groups = remember(visible) {
        visible.groupBy { it.periodKey }.entries.sortedByDescending { it.key }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Οφειλές") },
                navigationIcon = { MenuButton(onMenu) },
                actions = {
                    IconButton(onClick = { openFolder() }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = "Φάκελος στο Drive")
                    }
                    IconButton(onClick = { askAgency = true }) {
                        Icon(Icons.Default.UploadFile, contentDescription = "Εισαγωγή αρχείου")
                    }
                    IconButton(onClick = { scanDrive() }) {
                        Icon(Icons.Default.CloudSync, contentDescription = "Σάρωση Drive")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = DebtEntity() }) {
                Icon(Icons.Default.Add, contentDescription = "Νέα οφειλή")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { TotalsCard(debts) }

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
                        val count = debts.count { it.agency == candidate && !it.paid }
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
                        onToggle = { scope.launch { repository.setPaid(debt.id, !debt.paid) } },
                        onOpen = { editing = debt },
                    )
                }
            }
        }
    }

    if (askAgency) {
        AgencyPicker(
            onDismiss = { askAgency = false },
            onPick = { chosen ->
                askAgency = false
                pickingFor = chosen
                picker.launch(arrayOf("*/*"))
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
private fun DebtRow(debt: DebtEntity, onToggle: () -> Unit, onOpen: () -> Unit) {
    val today = LocalDate.now()
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface),
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
                    debt.title,
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
            Text(
                debt.amount.asMoney(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Checkbox(checked = debt.paid, onCheckedChange = { onToggle() })
        }
    }
}

private fun subtitleFor(debt: DebtEntity, today: LocalDate): String = buildString {
    append(debt.kind.label)
    append(" · ")
    append(debt.periodLabel)
    debt.dueDay?.let { due ->
        append(" · λήξη ")
        append(due.asOfferDate())
        val left = debt.daysLeft(today)
        if (!debt.paid && left != null && left < 0) append(" (πέρασε)")
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

/** Το όνομα του αρχείου όπως το δείχνει ο επιλογέας, για να ταξιδέψει στο Drive. */
private fun displayName(context: android.content.Context, uri: Uri): String {
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
