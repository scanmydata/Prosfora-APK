package gr.prosfora.app.ui.employees

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.debt.DebtRepository
import gr.prosfora.app.sync.EmployeeIndexReconciler
import gr.prosfora.app.sync.PayrollEmployeeSnapshotStore
import gr.prosfora.app.ui.MenuButton
import gr.prosfora.app.util.asMoney
import kotlinx.coroutines.launch

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
        people.filter {
            q.isBlank() ||
                it.name.lowercase().contains(q) ||
                it.alias.lowercase().contains(q) ||
                it.amIka.contains(q)
        }
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
                val totals = PayrollEmployeeSnapshotStore.totals(employee)
                Card(
                    onClick = { selected = employee },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    employee.display,
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (employee.alias.isNotBlank()) BrandGreen else MaterialTheme.colorScheme.onSurface,
                                )
                                if (employee.alias.isNotBlank()) {
                                    Text(employee.name, style = MaterialTheme.typography.bodyMedium)
                                }
                                Text("ΑΜ ΙΚΑ: ${employee.amIka}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text("Κωδικός: ${employee.code.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { selected = employee }) {
                                Icon(Icons.Default.Edit, contentDescription = "Άνοιγμα καρτέλας", tint = BrandGreen)
                            }
                            IconButton(onClick = { deleting = employee }) {
                                Icon(Icons.Default.Delete, contentDescription = "Διαγραφή εργαζομένου", tint = DeleteRed)
                            }
                        }
                        Text("Σύνολο ενσήμων: ${totals.insuranceDays}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        Text("Συνολικά πληρωτέα: ${totals.payable.asMoney()}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        Text("Κόστος ενσήμων: ${totals.insuranceCost.asMoney()}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                    }
                }
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
            text = { Text("Θα αφαιρεθεί ο «${employee.display}» από το ευρετήριο. Οι οφειλές και τα αποθηκευμένα ιστορικά ποσά της καρτέλας δεν διαγράφονται.") },
            confirmButton = {
                TextButton(onClick = {
                    val victim = employee
                    deleting = null
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        repository.deleteEmployee(victim.id)
                        Toast.makeText(context, "Ο εργαζόμενος αφαιρέθηκε από το ευρετήριο.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Διαγραφή", color = DeleteRed) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Άκυρο", color = BrandGreen) } },
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

    val totals = PayrollEmployeeSnapshotStore.totals(employee)
    val rows = debts
        .filter { it.kind.perPerson && it.amIka == employee.amIka }
        .sortedWith(compareByDescending<DebtEntity> { it.periodYear }.thenByDescending { it.periodMonth }.thenBy { it.kind.ordinal })

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(employee.display, fontWeight = FontWeight.Bold)
                        if (employee.alias.isNotBlank()) Text(employee.name, style = MaterialTheme.typography.labelSmall)
                        Text("ΑΜ ΙΚΑ ${employee.amIka}", style = MaterialTheme.typography.labelSmall, color = BrandGreen)
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
            item {
                Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("ΑΠΟΘΗΚΕΥΜΕΝΑ ΣΤΟΙΧΕΙΑ ΚΑΡΤΕΛΑΣ", style = MaterialTheme.typography.labelLarge, color = BrandGreen, fontWeight = FontWeight.Bold)
                        Text("Σύνολο πληρωτέο: ${totals.payable.asMoney()}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text("Σύνολο ενσήμων: ${totals.insuranceDays}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text("Κόστος ενσήμων: ${totals.insuranceCost.asMoney()}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                    }
                }
            }

            if (rows.isEmpty()) {
                item { Text("Δεν υπάρχουν ενεργές μισθοδοτικές οφειλές. Τα αποθηκευμένα ποσά της καρτέλας παραμένουν διαθέσιμα.") }
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
                    Text("ΑΜ ΙΚΑ: ${employee.amIka}", color = BrandGreen, fontWeight = FontWeight.Bold)
                    Text(employee.name)
                    OutlinedTextField(
                        value = alias,
                        onValueChange = { alias = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Ψευδώνυμο") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val newEmployee = employee.copy(alias = alias.trim())
                    scope.launch {
                        repository.saveEmployee(newEmployee)
                        showEdit = false
                        Toast.makeText(context, "Αποθηκεύτηκε το ψευδώνυμο.", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Αποθήκευση", color = BrandGreen) }
            },
            dismissButton = { TextButton(onClick = { showEdit = false }) { Text("Άκυρο", color = BrandGreen) } },
        )
    }
}
