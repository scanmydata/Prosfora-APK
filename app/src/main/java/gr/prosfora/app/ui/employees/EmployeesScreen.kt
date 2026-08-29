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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateMapOf
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
import gr.prosfora.app.debt.DocumentText
import gr.prosfora.app.google.DriveClient
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.SheetsClient
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.sync.SheetSync
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
    val debts by repository.observeAll().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<EmployeeEntity?>(null) }
    var deletingEmployee by remember { mutableStateOf<EmployeeEntity?>(null) }

    LaunchedEffect(debts) {
        repository.ensureEmployeesFromExistingDebts()
    }

    if (selected != null) {
        EmployeeDetailScreen(selected!!, debts, context, repository) { selected = null }
        return
    }

    val filtered = remember(people, query) {
        val q = query.trim().lowercase()
        people.filter { employee ->
            q.isBlank() ||
                employee.name.lowercase().contains(q) ||
                employee.alias.lowercase().contains(q) ||
                employee.amIka.contains(q)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Εργαζόμενοι") },
                navigationIcon = { gr.prosfora.app.ui.MenuButton(onMenu) },
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
                    label = { Text("Αναζήτηση ονοματεπωνύμου, ψευδωνύμου ή ΑΜ ΙΚΑ") },
                    placeholder = { Text("π.χ. BUTT HURARA ή 305389566") },
                )
            }
            items(filtered, key = { it.id }) { employee ->
                val rows = debts.filter { it.kind.perPerson && it.amIka == employee.amIka }
                Card(
                    onClick = { selected = employee },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                if (employee.alias.isNotBlank()) {
                                    Text(employee.alias, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = BrandGreen)
                                }
                                Text(employee.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text("ΑΜ ΙΚΑ: ${employee.amIka.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text("Κωδικός: ${employee.code.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { selected = employee }) {
                                Icon(Icons.Default.Edit, contentDescription = "Επεξεργασία", tint = BrandGreen)
                            }
                            IconButton(onClick = { deletingEmployee = employee }) {
                                Icon(Icons.Default.Delete, contentDescription = "Διαγραφή από ευρετήριο", tint = DeleteRed)
                            }
                        }
                        Text("Συνολικά πληρωτέα: ${rows.sumOf { it.amount }.asMoney()}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                        Text("Άνοιγμα καρτέλας ανά μήνα / έτος →", style = MaterialTheme.typography.bodySmall, color = BrandGreen)
                    }
                }
            }
            if (filtered.isEmpty()) {
                item { Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) { Text("Δεν βρέθηκε εργαζόμενος.", modifier = Modifier.padding(16.dp)) } }
            }
        }
    }

    deletingEmployee?.let { employee ->
        AlertDialog(
            onDismissRequest = { deletingEmployee = null },
            title = { Text("Διαγραφή εργαζομένου") },
            text = {
                Text(
                    "Θα αφαιρεθεί ο «${employee.display}» από το ευρετήριο εργαζομένων. Οι υπάρχουσες μισθοδοσίες και το ιστορικό του δεν θα διαγραφούν.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val victim = employee
                        deletingEmployee = null
                        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                            repository.deleteEmployee(victim.id)
                            Toast.makeText(context, "Ο εργαζόμενος αφαιρέθηκε από το ευρετήριο.", Toast.LENGTH_SHORT).show()
                        }
                    },
                ) { Text("Διαγραφή", color = DeleteRed) }
            },
            dismissButton = { TextButton(onClick = { deletingEmployee = null }) { Text("Άκυρο", color = BrandGreen) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmployeeDetailScreen(
    employee: EmployeeEntity,
    debts: List<DebtEntity>,
    context: Context,
    repository: DebtRepository,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()
    val settings = remember { GoogleSettings(context) }
    var sheetsClient by remember(employee.id) { mutableStateOf<SheetsClient?>(null) }
    val rows = remember(debts, employee.id) { debts.filter { it.kind.perPerson && it.amIka == employee.amIka } }
    val years = remember(rows) { rows.map { it.periodYear }.filter { it > 0 }.distinct().sortedDescending().ifEmpty { listOf(java.time.LocalDate.now().year) } }
    var year by remember(years) { mutableStateOf(years.first()) }
    var showEdit by remember { mutableStateOf(false) }
    var showAnnual by remember { mutableStateOf(true) }
    var alias by remember(employee) { mutableStateOf(employee.alias) }
    var savingAlias by remember { mutableStateOf(false) }
    var cacheLoaded by remember(employee.id) { mutableStateOf(false) }
    val costByMonth = remember(employee.id) { mutableStateMapOf<String, Double>() }
    val loadingMonths = remember(employee.id) { mutableStateMapOf<String, Boolean>() }

    fun keyFor(targetYear: Int, month: Int) = "$targetYear-$month"

    suspend fun saveCostToSheet(targetYear: Int, month: Int, cost: Double, sourceFileId: String) {
        val client = sheetsClient ?: return
        val spreadsheetId = settings.spreadsheetId ?: return
        val existing = client.sheetTitles(spreadsheetId)
        if (SheetSync.TAB_EMPLOYEE_COSTS !in existing) client.addSheet(spreadsheetId, SheetSync.TAB_EMPLOYEE_COSTS)
        val current = client.readRows(spreadsheetId, SheetSync.TAB_EMPLOYEE_COSTS)
        val data = current.drop(1).filter { it.firstOrNull()?.isNotBlank() == true }
            .map { row -> List(SheetSync.EMPLOYEE_COST_HEADER.size) { i -> row.getOrElse(i) { "" } } }.toMutableList()
        val key = keyFor(targetYear, month)
        val replacement = listOf(employee.id, employee.name, targetYear.toString(), month.toString(), rows.filter { it.periodYear == targetYear && it.periodMonth == month }.sumOf { it.amount }.toString(), cost.toString(), sourceFileId, System.currentTimeMillis().toString())
        val index = data.indexOfFirst { it[0] == employee.id && keyFor(it[2].toIntOrNull() ?: -1, it[3].toIntOrNull() ?: -1) == key }
        if (index >= 0) data[index] = replacement else data += replacement
        client.replaceRows(spreadsheetId, SheetSync.TAB_EMPLOYEE_COSTS, listOf(SheetSync.EMPLOYEE_COST_HEADER) + data)
    }

    suspend fun computeMonthIfNeeded(targetYear: Int, month: Int, monthRows: List<DebtEntity>) {
        val key = keyFor(targetYear, month)
        if (!cacheLoaded || costByMonth.containsKey(key) || loadingMonths[key] == true) return
        val source = monthRows.map { it.driveFileId to it.source }.distinct().firstOrNull { it.first.isNotBlank() } ?: return
        loadingMonths[key] = true
        val value = runCatching {
            val drive = DriveClient(authorizer.accessToken())
            val bytes = drive.download(source.first)
            val reader = DocumentText(drive, settings.ocrApiKey.takeIf { it.isNotBlank() }?.let { gr.prosfora.app.debt.OcrSpaceClient(it) })
            val text = reader.read(bytes, source.first, source.second) { it.contains("Μισθοδοτική", ignoreCase = true) || it.contains("ΜΙΣΘΟΔΟΤΙΚΗ", ignoreCase = true) }.text
            PayrollInsuranceCostExtractor.find(text, employee)
        }.getOrElse { 0.0 }
        costByMonth[key] = value
        runCatching { saveCostToSheet(targetYear, month, value, source.first) }
        loadingMonths[key] = false
    }

    LaunchedEffect(employee.id) {
        costByMonth.clear(); loadingMonths.clear(); cacheLoaded = false
        val client = runCatching { SheetsClient(authorizer.accessToken()) }.getOrNull()
        sheetsClient = client
        val spreadsheetId = settings.spreadsheetId
        if (client != null && spreadsheetId != null) runCatching {
            val existing = client.sheetTitles(spreadsheetId)
            if (SheetSync.TAB_EMPLOYEE_COSTS !in existing) {
                client.addSheet(spreadsheetId, SheetSync.TAB_EMPLOYEE_COSTS)
                client.replaceRows(spreadsheetId, SheetSync.TAB_EMPLOYEE_COSTS, listOf(SheetSync.EMPLOYEE_COST_HEADER))
            }
            client.readRows(spreadsheetId, SheetSync.TAB_EMPLOYEE_COSTS).drop(1).forEach { row ->
                if (row.getOrElse(0) { "" } == employee.id) {
                    val y = row.getOrElse(2) { "" }.toIntOrNull() ?: return@forEach
                    val m = row.getOrElse(3) { "" }.toIntOrNull() ?: return@forEach
                    val c = row.getOrElse(5) { "" }.replace(",", ".").toDoubleOrNull() ?: return@forEach
                    costByMonth[keyFor(y, m)] = c
                }
            }
        }
        cacheLoaded = true
    }

    val selectedRows = rows.filter { it.periodYear == year }
    val months = selectedRows.groupBy { it.periodMonth }.entries.sortedByDescending { it.key }
    LaunchedEffect(year, selectedRows, cacheLoaded) {
        if (cacheLoaded) months.forEach { (month, monthRows) -> computeMonthIfNeeded(year, month, monthRows) }
    }

    val annualPayroll = selectedRows.sumOf { it.amount }
    val annualCost = months.sumOf { (month, _) -> costByMonth[keyFor(year, month)] ?: 0.0 }
    val loading = months.any { (month, _) -> loadingMonths[keyFor(year, month)] == true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (employee.alias.isNotBlank()) employee.alias else employee.name, fontWeight = FontWeight.Bold)
                        if (employee.alias.isNotBlank()) Text(employee.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("ΑΜ ΙΚΑ ${employee.amIka}", style = MaterialTheme.typography.labelSmall, color = BrandGreen)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Πίσω") } },
                actions = { IconButton(onClick = { showEdit = true }) { Icon(Icons.Default.Edit, contentDescription = "Επεξεργασία", tint = BrandGreen) } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Έτος", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp))
                    years.forEach { candidate -> FilterChip(selected = candidate == year, onClick = { year = candidate }, label = { Text(candidate.toString()) }, modifier = Modifier.padding(end = 6.dp)) }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = showAnnual, onCheckedChange = { showAnnual = it })
                    Text("Εμφάνιση ετήσιου συνόλου", fontWeight = FontWeight.SemiBold)
                }
            }
            months.forEach { (month, monthRows) ->
                val monthlyPayable = monthRows.sumOf { it.amount }
                val monthlyCost = costByMonth[keyFor(year, month)] ?: 0.0
                val allPaid = monthRows.isNotEmpty() && monthRows.all { it.paid }
                item(key = "employee-month-$year-$month") {
                    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${month.toString().padStart(2, '0')}/$year", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Text("ΠΛΗΡΩΤΕΟ", style = MaterialTheme.typography.labelMedium, color = BrandGreen, fontWeight = FontWeight.Bold)
                            Text(monthlyPayable.asMoney(), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                            Text("Κόστος ενσήμων: ${monthlyCost.asMoney()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (allPaid) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = BrandGreen)
                                    Text(" Πληρωμένο", color = BrandGreen, fontWeight = FontWeight.Bold)
                                }
                            } else {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            monthRows.filterNot { it.paid }.forEach { repository.setPaid(it.id, true) }
                                            Toast.makeText(context, "Ολοκληρώθηκε η πληρωμή $month/$year", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Ολοκλήρωση πληρωμής") }
                            }
                        }
                    }
                }
            }
            if (showAnnual) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = BrandGreen), shape = RoundedCornerShape(16.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("ΣΥΝΟΛΟ $year", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = Color.Black)
                            Text("ΠΛΗΡΩΤΕΟ", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = Color.Black)
                            Text(annualPayroll.asMoney(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold, color = Color.Black)
                            Text("Κόστος ενσήμων: ${annualCost.asMoney()}", style = MaterialTheme.typography.bodyMedium, color = Color.Black)
                            if (loading) Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp, color = Color.Black)
                                Text("Υπολογισμός…", color = Color.Black)
                            }
                        }
                    }
                }
            }
            if (months.isEmpty()) item { Text("Δεν υπάρχουν μισθοδοτικές εγγραφές για το $year.") }
        }
    }

    if (showEdit) {
        AlertDialog(
            onDismissRequest = { showEdit = false },
            title = { Text("Επεξεργασία εργαζομένου") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("ΑΜ ΙΚΑ: ${employee.amIka}", style = MaterialTheme.typography.bodySmall, color = BrandGreen, fontWeight = FontWeight.Bold)
                    Text(employee.name, style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(value = alias, onValueChange = { alias = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("Ψευδώνυμο") })
                }
            },
            confirmButton = {
                TextButton(enabled = !savingAlias, onClick = {
                    savingAlias = true
                    scope.launch {
                        repository.saveEmployee(employee.copy(alias = alias.trim()))
                        savingAlias = false; showEdit = false
                        Toast.makeText(context, "Αποθηκεύτηκε το ψευδώνυμο", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Αποθήκευση", color = BrandGreen) }
            },
            dismissButton = { TextButton(onClick = { showEdit = false }) { Text("Άκυρο", color = BrandGreen) } },
        )
    }
}

private object PayrollInsuranceCostExtractor {
    private val amountRegex = Regex("[0-9][0-9.]*,[0-9]{2}")
    fun find(text: String, employee: EmployeeEntity): Double {
        if (text.isBlank()) return 0.0
        val wanted = employee.name.trim().split(Regex("\\s+")).filter { it.length >= 2 }
        val lines = text.lines()
        val start = lines.indexOfFirst { line -> line.contains(employee.code) && wanted.all { token -> line.contains(token, ignoreCase = true) } }
        if (start < 0) return 0.0
        for (index in start + 1 until lines.size) {
            val line = lines[index].trim()
            if (line.isBlank()) continue
            if (Regex("^\\d{1,3}\\s+[A-ZΑ-Ω0-9]{2,6}\\s+").containsMatchIn(line)) break
            if (line.any { it.isLetter() }) continue
            val numbers = line.split(Regex("\\s+")).mapNotNull { token -> amountRegex.matchEntire(token)?.value?.replace(".", "")?.replace(',', '.')?.toDoubleOrNull() }
            if (numbers.size >= 8) return numbers.getOrNull(3) ?: 0.0
        }
        return 0.0
    }
}
