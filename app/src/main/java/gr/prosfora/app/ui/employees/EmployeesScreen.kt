package gr.prosfora.app.ui.employees

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.debt.DebtRepository
import gr.prosfora.app.sync.EmployeeIndexReconciler
import gr.prosfora.app.sync.PayrollEmployeeSnapshotStore
import gr.prosfora.app.ui.MenuButton
import gr.prosfora.app.ui.debts.monthLabel
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.asOfferDate
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Calendar

private val BrandGreen = Color(0xFF00E2A2)
private val DeleteRed = Color(0xFFD32F2F)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmployeesScreen(onMenu: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { DebtRepository(context) }
    val people by repository.observeEmployees().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<EmployeeEntity?>(null) }
    var deleting by remember { mutableStateOf<EmployeeEntity?>(null) }

    LaunchedEffect(Unit) { EmployeeIndexReconciler.rebuild(context) }

    if (selected != null) {
        EmployeeDetailScreen(
            employee = selected!!,
            context = context,
            repository = repository,
            onBack = { selected = null },
        )
        return
    }

    val filtered = remember(people, query) {
        val q = query.trim().lowercase()
        people
            .filter {
                q.isBlank() ||
                    it.name.lowercase().contains(q) ||
                    it.alias.lowercase().contains(q) ||
                    it.amIka.contains(q)
            }
            // Όποιος αποχώρησε πέφτει στο τέλος: δεν κρύβεται, αλλά ούτε
            // στέκεται ανάμεσα σε αυτούς που πληρώνονται κάθε μήνα
            .sortedWith(compareBy({ it.gone() }, { it.display.uppercase() }))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Εργαζόμενοι") },
                navigationIcon = { MenuButton(onMenu) },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    label = { Text("Αναζήτηση ονοματεπωνύμου, ψευδωνύμου ή ΑΜ ΙΚΑ") },
                )
            }

            items(filtered, key = { it.id }) { employee ->
                EmployeeCard(
                    employee = employee,
                    onOpen = { selected = employee },
                    onDelete = { deleting = employee },
                )
            }

            if (filtered.isEmpty()) {
                item {
                    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
                        Text("Δεν βρέθηκε εργαζόμενος.", modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }

    deleting?.let { employee ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Διαγραφή εργαζομένου") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Ο εργαζόμενος «${employee.display}» θα αφαιρεθεί από το ευρετήριο.")
                    Text("Τα αποθηκευμένα ιστορικά ποσά και οι οφειλές δεν διαγράφονται με την απλή αφαίρεση από το ευρετήριο.")
                    Text("Θέλεις να διαγραφεί οριστικά και από τη βάση δεδομένων;")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val victim = employee
                    deleting = null
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        repository.deleteEmployee(victim.id)
                        Toast.makeText(context, "Ο εργαζόμενος αφαιρέθηκε από το ευρετήριο.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Μόνο από ευρετήριο", color = BrandGreen) }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val victim = employee
                        deleting = null
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            repository.deleteEmployeeFromDatabase(victim.id)
                            Toast.makeText(context, "Ο εργαζόμενος διαγράφηκε από τη βάση.", Toast.LENGTH_SHORT).show()
                        }
                    }) { Text("Και από βάση", color = DeleteRed) }
                    TextButton(onClick = { deleting = null }) { Text("Άκυρο", color = BrandGreen) }
                }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmployeeDetailScreen(
    employee: EmployeeEntity,
    context: Context,
    repository: DebtRepository,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val debts by repository.observeAll().collectAsState(initial = emptyList())
    var showEdit by remember { mutableStateOf(false) }
    var alias by remember(employee) { mutableStateOf(employee.alias) }
    var leftDay by remember(employee) { mutableStateOf(employee.leftDay) }
    var pickingLeft by remember { mutableStateOf(false) }
    var showAnnualTotals by remember { mutableStateOf(true) }

    val history = PayrollEmployeeSnapshotStore.history(employee)
    val totals = PayrollEmployeeSnapshotStore.totals(employee)
    val rows = debts
        .filter { it.kind.perPerson && it.amIka == employee.amIka }
        .sortedWith(compareByDescending<DebtEntity> { it.periodYear }.thenByDescending { it.periodMonth }.thenBy { it.kind.ordinal })
    val currentYear = Calendar.getInstance().get(Calendar.YEAR)
    val years = history.map { it.year }.distinct().sortedDescending()
    var year by remember(employee, years) {
        mutableStateOf(years.firstOrNull { it == currentYear } ?: years.firstOrNull() ?: currentYear)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(employee.display, fontWeight = FontWeight.Bold)
                        if (employee.alias.isNotBlank()) Text(employee.name, style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Πίσω") } },
                actions = { IconButton(onClick = { showEdit = true }) { Icon(Icons.Default.Edit, contentDescription = "Επεξεργασία", tint = BrandGreen) } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Έτη σε chips, όπως στις Οφειλές: μία χρονιά τη φορά, νεότερη πρώτα
            if (years.isNotEmpty()) {
                item {
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
            }

            // Τα σύνολα του έτους σε μία σειρά, χωρίς κάρτα: τρεις αριθμοί δεν
            // χρειάζονται μισή οθόνη, και η ανάλυση των μηνών από κάτω τους
            // επαναλαμβάνει ούτως ή άλλως
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "Σύνολα $year",
                            style = MaterialTheme.typography.titleSmall,
                            color = BrandGreen,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = showAnnualTotals,
                            onCheckedChange = { showAnnualTotals = it },
                        )
                    }
                    if (showAnnualTotals) {
                        val ofYear = PayrollEmployeeSnapshotStore.totals(employee, year)
                        Text(
                            "${ofYear.payable.asMoney()} · ${ofYear.insuranceDays} ένσημα · " +
                                "κόστος ${ofYear.insuranceCost.asMoney()}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            val monthsOfYear = history.filter { it.year == year }
            if (monthsOfYear.isNotEmpty()) {
                item {
                    Text(
                        "ΑΝΑΛΥΣΗ ΑΝΑ ΜΗΝΑ",
                        style = MaterialTheme.typography.titleMedium,
                        color = BrandGreen,
                        fontWeight = FontWeight.Bold,
                    )
                }
                items(monthsOfYear, key = { "snapshot-${it.year}-${it.month}" }) { month ->
                    Card(
                        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                        shape = RoundedCornerShape(14.dp),
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    monthLabel(month.month, month.year),
                                    color = BrandGreen,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    month.payable.asMoney(),
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                )
                            }
                            Text(
                                "${month.insuranceDays} ένσημα · κόστος ${month.insuranceCost.asMoney()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            item { Text("ΕΝΕΡΓΕΣ ΟΦΕΙΛΕΣ ΜΙΣΘΟΔΟΣΙΑΣ", style = MaterialTheme.typography.titleMedium, color = BrandGreen, fontWeight = FontWeight.Bold) }
            if (rows.isEmpty()) {
                item { Text("Δεν υπάρχουν ενεργές μισθοδοτικές οφειλές. Η αποθηκευμένη μηνιαία ανάλυση παραμένει διαθέσιμη.") }
            } else {
                items(rows, key = { it.id }) { debt ->
                    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(14.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(debt.periodLabel, fontWeight = FontWeight.Bold, color = BrandGreen)
                            Text(debt.kind.label, fontWeight = FontWeight.SemiBold)
                            Text(debt.amount.asMoney(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(if (debt.paid) "Πληρωμένο" else "Απλήρωτο", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    if (showEdit) {
        AlertDialog(
            onDismissRequest = { showEdit = false },
            title = { Text("Επεξεργασία εργαζομένου") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(employee.name, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = alias,
                        onValueChange = { alias = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Ψευδώνυμο") },
                    )
                    // Η αποχώρηση δεν κρύβει τον εργαζόμενο και δεν αγγίζει
                    // τις μισθοδοσίες του: τον στέλνει στο τέλος της λίστας
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            leftDay?.let { "Αποχώρησε ${it.asOfferDate()}" } ?: "Χωρίς αποχώρηση",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { pickingLeft = true }) { Text("Ορισμός", color = BrandGreen) }
                        if (leftDay != null) {
                            TextButton(onClick = { leftDay = null }) { Text("Καθαρισμός", color = BrandGreen) }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val newEmployee = employee.copy(alias = alias.trim(), leftDay = leftDay)
                    scope.launch {
                        repository.saveEmployee(newEmployee)
                        showEdit = false
                        Toast.makeText(context, "Η καρτέλα αποθηκεύτηκε.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Αποθήκευση", color = BrandGreen) }
            },
            dismissButton = { TextButton(onClick = { showEdit = false }) { Text("Άκυρο", color = BrandGreen) } },
        )
    }

    if (pickingLeft) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (leftDay ?: LocalDate.now().toEpochDay()) * 86_400_000L,
        )
        DatePickerDialog(
            onDismissRequest = { pickingLeft = false },
            confirmButton = {
                TextButton(onClick = {
                    // Ο επιλογέας δουλεύει σε UTC· η μετατροπή γίνεται εκεί,
                    // αλλιώς η τοπική ζώνη μετακινεί τη μέρα κατά μία
                    state.selectedDateMillis?.let { millis ->
                        leftDay = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC)
                            .toLocalDate().toEpochDay()
                    }
                    pickingLeft = false
                }) { Text("Επιλογή", color = BrandGreen) }
            },
            dismissButton = {
                TextButton(onClick = { pickingLeft = false }) { Text("Άκυρο", color = BrandGreen) }
            },
        ) { DatePicker(state = state) }
    }
}

/**
 * Η καρτέλα ενός εργαζόμενου στη λίστα, με το ίδιο σχήμα που έχουν οι οφειλές:
 * κουκκίδα κατάστασης, όνομα και περίοδος αριστερά, ποσό δεξιά.
 *
 * **Ο τρέχων μήνας κυριαρχεί.** Αυτό ρωτάει κανείς ανοίγοντας τη λίστα — τι
 * τρέχει τώρα, όχι τι αθροίστηκε μέσα στη χρονιά. Τα σύνολα του έτους μένουν
 * από κάτω, σε μικρότερο μέγεθος.
 *
 * Ο ΑΜ ΙΚΑ δεν εμφανίζεται: είναι ο σύνδεσμος της καρτέλας, όχι πληροφορία που
 * χρειάζεται κανείς να διαβάσει. Η αναζήτηση εξακολουθεί να τον δέχεται.
 */
@Composable
private fun EmployeeCard(
    employee: EmployeeEntity,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val history = PayrollEmployeeSnapshotStore.history(employee)
    val now = Calendar.getInstance()
    val thisYear = now.get(Calendar.YEAR)
    val thisMonth = now.get(Calendar.MONTH) + 1

    // Ο τρέχων μήνας αν υπάρχει· αλλιώς ο πιο πρόσφατος που έχει καταγραφεί,
    // ώστε η καρτέλα να μη δείχνει ποτέ άδεια όταν η μισθοδοσία αργεί
    val current = history.firstOrNull { it.year == thisYear && it.month == thisMonth }
        ?: history.maxByOrNull { it.year * 100 + it.month }
    val yearly = PayrollEmployeeSnapshotStore.totals(employee, current?.year ?: thisYear)
    val away = employee.gone()
    val faded = MaterialTheme.colorScheme.onSurfaceVariant

    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (away) faded else BrandGreen),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        employee.display,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = if (away) faded else MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        buildString {
                            append(current?.let { monthLabel(it.month, it.year) } ?: "Καμία μισθοδοσία")
                            current?.let { append(" · ${it.insuranceDays} ένσημα") }
                            employee.leftDay?.let { append(" · αποχώρησε ${it.asOfferDate()}") }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = faded,
                    )
                }
                Text(
                    (current?.payable ?: 0.0).asMoney(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (away) faded else MaterialTheme.colorScheme.onSurface,
                )
            }

            if (current != null) {
                Text(
                    "Κόστος ενσήμων μήνα ${current.insuranceCost.asMoney()}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                HorizontalDivider()
                Text(
                    "Έτος ${current.year} · πληρωτέα ${yearly.payable.asMoney()} · " +
                        "ένσημα ${yearly.insuranceDays} · κόστος ${yearly.insuranceCost.asMoney()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = faded,
                )
            }

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = onOpen) {
                    Icon(Icons.Default.Edit, contentDescription = "Άνοιγμα καρτέλας", tint = BrandGreen)
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Διαγραφή εργαζομένου", tint = DeleteRed)
                }
            }
        }
    }
}
