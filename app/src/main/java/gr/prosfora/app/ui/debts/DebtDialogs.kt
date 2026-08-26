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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import gr.prosfora.app.data.db.DebtAgency
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.DebtKind
import gr.prosfora.app.debt.DebtImporter
import gr.prosfora.app.debt.DebtParser
import gr.prosfora.app.ui.components.StableTextField
import gr.prosfora.app.ui.offers.DeleteRed
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.asOfferDate
import gr.prosfora.app.util.parseDecimal

/**
 * Σε ποιον φάκελο πάει το αρχείο που θα διαλέξει ο χρήστης.
 *
 * Ρωτιέται πριν τον επιλογέα και όχι μετά, γιατί ο φάκελος καθορίζει και πού
 * ανεβαίνει το αντίγραφο — το *είδος* μέσα στον φορέα (ΙΚΑ ή ΤΕΚΑ) το βρίσκει
 * μόνη της η ανάγνωση.
 */
@Composable
fun AgencyPicker(onDismiss: () -> Unit, onPick: (DebtAgency) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Τι παραστατικό είναι;") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Διάλεξε φορέα — εκεί θα μπει το αντίγραφο στο Drive.",
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing) "Οφειλή" else "Νέα οφειλή") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Φορέας", style = MaterialTheme.typography.labelMedium)
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
                StableTextField(
                    value = reference,
                    onValueChange = { reference = it },
                    label = "Ταυτότητα οφειλής / RF",
                    modifier = Modifier.fillMaxWidth(),
                )
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
                if (debt.source.isNotBlank()) {
                    Text(
                        "Από: ${debt.source}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
}

/**
 * Τι διαβάστηκε από τα παραστατικά, πριν μπει στη βάση.
 *
 * Το βήμα αυτό δεν παραλείπεται: η ανάγνωση περνάει από OCR σε κάποια έντυπα
 * και ένα λάθος ποσό που μπήκε σιωπηλά είναι χειρότερο από ένα που δεν μπήκε.
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
                if (report.unreadable.isNotEmpty()) {
                    Text(
                        "Δεν αναγνωρίστηκαν: ${report.unreadable.joinToString(", ")}",
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
