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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.rememberDatePickerState
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
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

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

private enum class InstallmentImportMode { TOTAL, INSTALLMENTS }

private fun lastBusinessDay(year: Int, month: Int): LocalDate {
    var date = YearMonth.of(year, month).atEndOfMonth()
    while (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
        date = date.minusDays(1)
    }
    return date
}

private fun roundMoney(value: Double): Double = Math.round(value * 100.0) / 100.0

private fun materializeInstallmentDebt(
    debt: DebtEntity,
    plan: AadeInstallmentParser.Info,
    mode: InstallmentImportMode,
): List<DebtEntity> {
    if (mode == InstallmentImportMode.TOTAL) {
        return listOf(
            debt.copy(
                amount = plan.totalAmount,
                dueDay = plan.firstDueDay,
            ),
        )
    }

    val firstDue = LocalDate.ofEpochDay(plan.firstDueDay)
    return (0 until plan.installmentCount).map { index ->
        val targetMonth = firstDue.plusMonths(index.toLong())
        val due = if (index == 0) {
            firstDue
        } else {
            lastBusinessDay(targetMonth.year, targetMonth.monthValue)
        }
        val amount = if (index == plan.installmentCount - 1) {
            roundMoney(plan.totalAmount - plan.installmentAmount * (plan.installmentCount - 1))
        } else {
            plan.installmentAmount
        }
        debt.copy(
            id = DebtEntity.idFor(
                debt.kind,
                debt.periodYear,
                debt.periodMonth,
                "${debt.reference}|dose:${index + 1}/${plan.installmentCount}",
                debt.personName,
            ),
            amount = amount,
            dueDay = due.toEpochDay(),
            description = buildString {
                append(debt.description.ifBlank { "Βεβαιωμένη οφειλή" })
                append(" · δόση ${index + 1}/${plan.installmentCount}")
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
    val chosen = remember(report) {
        mutableStateMapOf<String, Boolean>().apply { debts.forEach { put(it.id, true) } }
    }
    val installmentModes = remember(report) {
        mutableStateMapOf<String, InstallmentImportMode>().apply {
            report.found.forEach { found ->
                if (found.installmentPlan != null) {
                    put(found.driveFileId, InstallmentImportMode.TOTAL)
                }
            }
        }
    }
    val selected = debts.filter { chosen[it.id] == true }
    val materializedSelected = selected.flatMap { debt ->
        val plan = report.found.firstOrNull { it.driveFileId == debt.driveFileId }?.installmentPlan
        if (plan == null) {
            listOf(debt)
        } else {
            materializeInstallmentDebt(
                debt,
                plan,
                installmentModes[debt.driveFileId] ?: InstallmentImportMode.TOTAL,
            )
        }
    }

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
                report.found.filter { it.installmentPlan != null }.forEach { found ->
                    val plan = found.installmentPlan ?: return@forEach
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.secondaryContainer)
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            "ΑΑΔΕ σε δόσεις — ${found.fileName}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Συνολικό ${plan.totalAmount.asMoney()} · δόση ${plan.installmentAmount.asMoney()} · " +
                                "${plan.installmentCount} δόσεις · πρώτη ${plan.firstDueDay.asOfferDate()}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = installmentModes[found.driveFileId] == InstallmentImportMode.TOTAL,
                                onClick = {
                                    installmentModes[found.driveFileId] = InstallmentImportMode.TOTAL
                                },
                                label = { Text("Συνολική οφειλή") },
                            )
                            FilterChip(
                                selected = installmentModes[found.driveFileId] == InstallmentImportMode.INSTALLMENTS,
                                onClick = {
                                    installmentModes[found.driveFileId] = InstallmentImportMode.INSTALLMENTS
                                },
                                label = { Text("Δημιουργία δόσεων") },
                            )
                        }
                    }
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
                if (materializedSelected.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    Text(
                        "Σύνολο επιλεγμένων: ${materializedSelected.sumOf { it.amount }.asMoney()} · " +
                            "εγγραφές: ${materializedSelected.size}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = materializedSelected.isNotEmpty(),
                onClick = { onConfirm(materializedSelected) },
            ) { Text("Αποθήκευση") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Άκυρο") } },
    )
}

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
                people.sortedBy { it.gone() }.forEach { employee ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { editing = employee }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                employee.display,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (employee.gone()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                },
                            )
                            val note = buildString {
                                if (employee.alias.isNotBlank()) append(employee.name)
                                employee.leftDay?.let {
                                    if (isNotEmpty()) append(" · ")
                                    append("αποχώρησε ${it.asOfferDate()}")
                                }
                            }
                            if (note.isNotBlank()) {
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        totals[employee.id]?.takeIf { it > 0.0 }?.let {
                            Text(it.asMoney(), style = MaterialTheme.typography.bodyMedium)
                        }
                        IconButton(onClick = { editing = employee }) {
                            Icon(Icons.Default.Edit, contentDescription = "Επεξεργασία")
                        }
                    }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Κλείσιμο") } },
    )

    editing?.let { employee ->
        EmployeeEditor(
            employee = employee,
            onDismiss = { editing = null },
            onSave = {
                editing = null
                scope.launch { repository.saveEmployee(it) }
            },
            onDelete = {
                editing = null
                scope.launch { repository.deleteEmployee(employee.id) }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmployeeEditor(
    employee: EmployeeEntity,
    onDismiss: () -> Unit,
    onSave: (EmployeeEntity) -> Unit,
    onDelete: () -> Unit,
) {
    var alias by remember(employee.id) { mutableStateOf(employee.alias) }
    var left by remember(employee.id) { mutableStateOf(employee.leftDay) }
    var picking by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(employee.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StableTextField(
                    value = alias,
                    onValueChange = { alias = it },
                    label = "Ψευδώνυμο",
                    placeholder = "όπως τον λες εσύ",
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        left?.let { "Αποχώρησε ${it.asOfferDate()}" } ?: "Χωρίς αποχώρηση",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { picking = true }) { Text("Ορισμός") }
                    if (left != null) {
                        TextButton(onClick = { left = null }) { Text("Καθαρισμός") }
                    }
                }
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Διαγραφή από το ευρετήριο", color = DeleteRed)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(employee.copy(alias = alias.trim(), leftDay = left))
            }) { Text("Αποθήκευση") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Άκυρο") } },
    )

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (left ?: LocalDate.now().toEpochDay()) * 86_400_000L,
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        left = java.time.Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    }
                    picking = false
                }) { Text("Επιλογή") }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text("Άκυρο") }
            },
        ) {
            DatePicker(state = state)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Διαγραφή εργαζόμενου") },
            text = {
                Text(
                    "Φεύγει από το ευρετήριο μαζί με το ψευδώνυμό του. " +
                        "Οι μισθοδοσίες του μένουν όπως είναι.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Διαγραφή", color = DeleteRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Άκυρο") }
            },
        )
    }
}
