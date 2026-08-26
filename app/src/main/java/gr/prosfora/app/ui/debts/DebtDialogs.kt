package gr.prosfora.app.ui.debts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import gr.prosfora.app.data.db.DebtAgency
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.DebtKind
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.debt.DebtImporter
import gr.prosfora.app.debt.DebtParser
import gr.prosfora.app.debt.DebtRepository
import gr.prosfora.app.ui.components.StableTextField
import gr.prosfora.app.ui.offers.DeleteRed
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.asOfferDate
import gr.prosfora.app.util.parseDecimal
import kotlinx.coroutines.launch

/**
 * Σε ποιον φάκελο πάει το αρχείο που θα διαλέξει ο χρήστης.
 *
 * Ρωτιέται πριν τον επιλογέα και όχι μετά, γιατί ο φάκελος καθορίζει και πού
 * ανεβαίνει το αντίγραφο — το *είδος* μέσα στον φορέα (ΙΚΑ ή ΤΕΚΑ, μισθοδοσία
 * ή δώρο) το βρίσκει μόνη της η ανάγνωση.
 */
@Composable
fun AgencyPicker(onDismiss: () -> Unit, onPick: (DebtAgency) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Τι παραστατικό είναι;") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Διάλεξε φορέα — εκεί θα μπει το αντίγραφο στο Drive. " +
                        "Δέχεται PDF ή φωτογραφία / στιγμιότυπο οθόνης.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DebtAgency.entries.forEach { agency ->
                    Text(
                        agency.label,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(agency) }
                            .padding(vertical = 12.dp),
                    )
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Άκυρο") } },
    )
}

/**
 * Χειροκίνητη καταχώρηση ή διόρθωση μιας οφειλής.
 *
 * Είναι και ο δρόμος διόρθωσης για ό,τι διάβασε λάθος το OCR: όλα τα πεδία
 * μένουν ελεύθερα, τίποτα δεν κλειδώνει επειδή «ήρθε από αρχείο».
 */
@Composable
fun DebtEditorDialog(
    debt: DebtEntity,
    onDismiss: () -> Unit,
    onSave: (DebtEntity) -> Unit,
    onDelete: () -> Unit,
    onDeleteFile: () -> Unit,
    onCopy: (String) -> Unit,
) {
    val existing = debt.amount > 0.0 || debt.reference.isNotBlank() || debt.personName.isNotBlank()

    var kind by remember { mutableStateOf(debt.kind) }
    var amount by remember { mutableStateOf(if (debt.amount > 0) debt.amount.toString() else "") }
    var period by remember {
        mutableStateOf(if (debt.periodMonth > 0) debt.periodLabel else "")
    }
    var due by remember { mutableStateOf(debt.dueDay?.asOfferDate().orEmpty()) }
    var reference by remember { mutableStateOf(debt.reference) }
    var description by remember { mutableStateOf(debt.description) }
    var person by remember { mutableStateOf(debt.personName) }
    var paid by remember { mutableStateOf(debt.paid) }
    var confirmFile by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing) "Οφειλή" else "Νέα οφειλή") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Είδος", style = MaterialTheme.typography.labelMedium)
                    DebtKind.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { candidate ->
                                FilterChip(
                                    selected = kind == candidate,
                                    onClick = { kind = candidate },
                                    label = { Text(candidate.label, maxLines = 1) },
                                    leadingIcon = {
                                        Box(
                                            Modifier
                                                .size(10.dp)
                                                .background(candidate.color, CircleShape),
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (pair.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                }

                StableTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = "Ποσό (€)",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                StableTextField(
                    value = period,
                    onValueChange = { period = it },
                    label = "Μήνας αναφοράς",
                    placeholder = "π.χ. 7/2026",
                    modifier = Modifier.fillMaxWidth(),
                )
                StableTextField(
                    value = due,
                    onValueChange = { due = it },
                    label = "Λήξη πληρωμής",
                    placeholder = "π.χ. 31/8/2026",
                    modifier = Modifier.fillMaxWidth(),
                )
                if (kind.perPerson) {
                    StableTextField(
                        value = person,
                        onValueChange = { person = it },
                        label = "Εργαζόμενος",
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StableTextField(
                        value = reference,
                        onValueChange = { reference = it },
                        label = "Ταυτότητα οφειλής / RF",
                        modifier = Modifier.weight(1f),
                    )
                    if (reference.isNotBlank()) {
                        IconButton(onClick = { onCopy(reference) }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "Αντιγραφή")
                        }
                    }
                }
                StableTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = "Περιγραφή",
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = paid, onCheckedChange = { paid = it })
                    Text("Πληρώθηκε", style = MaterialTheme.typography.bodyMedium)
                }
                debt.paidDay?.takeIf { paid }?.let {
                    Text(
                        "Ημερομηνία εξόφλησης: ${it.asOfferDate()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (debt.source.isNotBlank()) {
                    HorizontalDivider()
                    Text(
                        "Από: ${debt.source}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    // Ένα λάθος αρχείο φτιάχνει δέκα γραμμές· να σβήνονται μαζί
                    TextButton(onClick = { confirmFile = true }) {
                        Text("Διαγραφή όλων από αυτό το αρχείο", color = DeleteRed)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = amount.parseDecimal() != null,
                onClick = {
                    val parsed = DebtParser.parsePeriod(period)
                    val month = parsed?.month ?: 0
                    val year = parsed?.year ?: 0
                    // Χωρίς ρητή λήξη μπαίνει η προθεσμία που ισχύει για το είδος
                    val dueDay = DebtParser.parseDay(due)
                        ?: DebtEntity.defaultDue(kind, year, month)
                    onSave(
                        debt.copy(
                            kind = kind,
                            amount = amount.parseDecimal() ?: 0.0,
                            periodMonth = month,
                            periodYear = year,
                            dueDay = dueDay,
                            reference = reference.filterNot { it.isWhitespace() },
                            description = description.trim(),
                            personName = if (kind.perPerson) person.trim() else "",
                            paid = paid,
                            paidAt = if (paid) debt.paidAt ?: System.currentTimeMillis() else null,
                            paidDay = if (paid) debt.paidDay else null,
                        ),
                    )
                },
            ) { Text("Αποθήκευση") }
        },
        dismissButton = {
            Row {
                if (existing) {
                    TextButton(onClick = onDelete) {
                        Text("Διαγραφή", color = DeleteRed)
                    }
                }
                TextButton(onClick = onDismiss) { Text("Άκυρο") }
            }
        },
    )

    if (confirmFile) {
        AlertDialog(
            onDismissRequest = { confirmFile = false },
            title = { Text("Διαγραφή εισαγωγής") },
            text = {
                Text(
                    "Θα σβηστούν όλες οι οφειλές που ήρθαν από το «${debt.source}». " +
                        "Το αρχείο μένει στο Drive.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmFile = false
                    onDeleteFile()
                }) { Text("Διαγραφή", color = DeleteRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmFile = false }) { Text("Άκυρο") }
            },
        )
    }
}

/**
 * Τι διαβάστηκε από τα παραστατικά, πριν μπει στη βάση.
 *
 * Το βήμα αυτό δεν παραλείπεται: η ανάγνωση περνάει από OCR σε κάποια έντυπα
 * και ένα λάθος ποσό που μπήκε σιωπηλά είναι χειρότερο από ένα που δεν μπήκε.
 * Γι' αυτό φαίνεται και **πώς** διαβάστηκε το καθένα.
 */
@Composable
fun DebtImportDialog(
    report: DebtImporter.Report,
    onDismiss: () -> Unit,
    onConfirm: (List<DebtEntity>) -> Unit,
) {
    val debts = report.debts
    // Όλα προεπιλεγμένα· η αρχικοποίηση γίνεται μία φορά και όχι σε κάθε
    // recomposition, αλλιώς το ξεμαρκάρισμα θα αναιρούνταν αμέσως
    val chosen = remember(report) {
        mutableStateMapOf<String, Boolean>().apply { debts.forEach { put(it.id, true) } }
    }
    val selected = debts.filter { chosen[it.id] == true }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Βρέθηκαν ${debts.size} οφειλές") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    report.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                report.unreadable.forEach { failed ->
                    Text(
                        "Δεν αναγνωρίστηκε: ${failed.fileName}" +
                            failed.note.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = DeleteRed,
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                debts.forEach { debt ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = chosen[debt.id] == true,
                            onCheckedChange = { chosen[debt.id] = it },
                        )
                        Box(
                            Modifier
                                .padding(end = 8.dp)
                                .size(10.dp)
                                .background(debt.kind.color, CircleShape),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(debt.title, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${debt.kind.label} · ${debt.periodLabel}" +
                                    (debt.dueDay?.let { " · λήξη ${it.asOfferDate()}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (debt.description.isNotBlank()) {
                                Text(
                                    debt.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(debt.amount.asMoney(), fontWeight = FontWeight.Bold)
                    }
                }
                if (selected.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(
                        "Σύνολο επιλεγμένων: ${selected.sumOf { it.amount }.asMoney()}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = selected.isNotEmpty(),
                onClick = { onConfirm(selected) },
            ) { Text("Αποθήκευση") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Άκυρο") } },
    )
}

/**
 * Το ευρετήριο των εργαζομένων.
 *
 * Στις μισθοδοτικές καταστάσεις τα ονόματα γράφονται με λατινικά κεφαλαία και
 * ανάποδα («BUTT HURARA»). Το ψευδώνυμο είναι πώς τους ξέρει ο χρήστης· ο
 * σύνδεσμος με το τυπωμένο όνομα μένει, ώστε η επόμενη κατάσταση να ταιριάξει
 * στο ίδιο άτομο.
 */
@Composable
fun EmployeeIndexDialog(
    repository: DebtRepository,
    debts: List<DebtEntity>,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val stream = remember(repository) { repository.observeEmployees() }
    val people by stream.collectAsState(initial = emptyList())
    var editing by remember { mutableStateOf<EmployeeEntity?>(null) }

    val totals = remember(debts) {
        debts.filter { it.personName.isNotBlank() && !it.paid }
            .groupBy { EmployeeEntity.idFor(it.personName) }
            .mapValues { (_, rows) -> rows.sumOf { it.amount } }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Εργαζόμενοι") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (people.isEmpty()) {
                    Text(
                        "Το ευρετήριο γεμίζει μόνο του με την πρώτη μισθοδοσία " +
                            "που θα εισαχθεί.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                people.forEach { employee ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { editing = employee }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(employee.display, style = MaterialTheme.typography.bodyLarge)
                            if (employee.alias.isNotBlank()) {
                                Text(
                                    employee.name,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        totals[employee.id]?.takeIf { it > 0.0 }?.let {
                            Text(it.asMoney(), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Κλείσιμο") } },
    )

    editing?.let { employee ->
        var alias by remember(employee.id) { mutableStateOf(employee.alias) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(employee.name) },
            text = {
                StableTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = "Ψευδώνυμο",
                    placeholder = "όπως τον λες εσύ",
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val updated = employee.copy(alias = alias.trim())
                    editing = null
                    scope.launch { repository.saveEmployee(updated) }
                }) { Text("Αποθήκευση") }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) { Text("Άκυρο") }
            },
        )
    }
}
