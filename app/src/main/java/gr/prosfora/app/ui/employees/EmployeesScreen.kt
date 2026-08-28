package gr.prosfora.app.ui.employees

import android.content.Context
import android.widget.Toast
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.util.asMoney
import kotlinx.coroutines.launch

private val BrandGreen = Color(0xFF00E2A2)

@Composable
fun EmployeesScreen(onMenu: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val repository = remember { DebtRepository(context) }
    val people by repository.observeEmployees().collectAsState(initial = emptyList())
    val debts by repository.observeAll().collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<EmployeeEntity?>(null) }

    if (selected != null) {
        EmployeeDetailScreen(selected!!, debts, context, repository) { selected = null }
        return
    }

    val filtered = remember(people, query) {
        val q = query.trim().lowercase()
        people.filter { employee ->
            q.isBlank() || employee.name.lowercase().contains(q) || employee.alias.lowercase().contains(q)
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
                    label = { Text("Αναζήτηση ονοματεπωνύμου ή ψευδωνύμου") },
                    placeholder = { Text("π.χ. BUTT HURARA ή το ψευδώνυμο") },
                )
            }
            items(filtered, key = { it.id }) { employee ->
                val rows = debts.filter { it.kind.perPerson && EmployeeEntity.idFor(it.personName) == employee.id }
                Card(
                    onClick = { selected = employee },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(employee.display, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(if (employee.alias.isBlank()) employee.name else employee.name + " · " + employee.alias, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Icon(Icons.Default.Edit, contentDescription = null, tint = BrandGreen)
                        }
                        Text("Κωδικός: ${employee.code.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                        Text("Συνολικά πληρωτέα: ${rows.sumOf { it.amount }.asMoney()}", style = MaterialTheme.typography.bodyMedium)
                        Text("Προβολή καρτέλας ανά μήνα / έτος →", style = MaterialTheme.typography.bodySmall, color = BrandGreen)
                    }
                }
            }
            if (filtered.isEmpty()) {
                item { Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) { Text("Δεν βρέθηκε εργαζόμενος.", modifier = Modifier.padding(16.dp)) } }
            }
        }
    }
}

@Composable
private fun EmployeeDetailScreen(employee: EmployeeEntity, debts: List<DebtEntity>, context: Context, repository: DebtRepository, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()
    val settings = remember { GoogleSettings(context) }
    val rows = remember(debts, employee.id) { debts.filter { it.kind.perPerson && EmployeeEntity.idFor(it.personName) == employee.id } }
    val years = remember(rows) { rows.map { it.periodYear }.filter { it > 0 }.distinct().sortedDescending().ifEmpty { listOf(java.time.LocalDate.now().year) } }
    var year by remember(years) { mutableStateOf(years.first()) }
    var alias by remember(employee) { mutableStateOf(employee.alias) }
    var savingAlias by remember { mutableStateOf(false) }
    val costByFile = remember { mutableStateMapOf<String, Double>() }
    val loadingFiles = remember { mutableStateMapOf<String, Boolean>() }

    fun ensureCost(fileId: String, fileName: String) {
        if (fileId.isBlank() || costByFile.containsKey(fileId) || loadingFiles[fileId] == true) return
        loadingFiles[fileId] = true
        scope.launch {
            val value = runCatching {
                val drive = DriveClient(authorizer.accessToken())
                val bytes = drive.download(fileId)
                val reader = DocumentText(drive, settings.ocrApiKey.takeIf { it.isNotBlank() }?.let { gr.prosfora.app.debt.OcrSpaceClient(it) })
                val text = reader.read(bytes, fileId, fileName) { it.contains("Μισθοδοτική", ignoreCase = true) || it.contains("ΜΙΣΘΟΔΟΤΙΚΗ", ignoreCase = true) }.text
                PayrollInsuranceCostExtractor.find(text, employee)
            }.getOrElse { 0.0 }
            costByFile[fileId] = value
            loadingFiles[fileId] = false
        }
    }

    val selectedRows = rows.filter { it.periodYear == year }
    LaunchedEffect(selectedRows) { selectedRows.map { it.driveFileId to it.source }.distinct().forEach { (id, source) -> ensureCost(id, source) } }

    val months = selectedRows.groupBy { it.periodMonth }.entries.sortedByDescending { it.key }
    val annualPayroll = selectedRows.sumOf { it.amount }
    val annualCost = selectedRows.map { it.driveFileId to it.source }.distinct().sumOf { (id, _) -> costByFile[id] ?: 0.0 }
    val loading = selectedRows.any { loadingFiles[it.driveFileId] == true }

    Scaffold(
        topBar = { TopAppBar(title = { Text(employee.display) }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Πίσω") } }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(employee.name, style = MaterialTheme.typography.bodyMedium)
                        Text("Κωδικός ${employee.code.ifBlank { "—" }}", style = MaterialTheme.typography.bodySmall)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = alias, onValueChange = { alias = it }, modifier = Modifier.weight(1f), singleLine = true, label = { Text("Ψευδώνυμο") })
                            TextButton(enabled = !savingAlias && alias != employee.alias, onClick = {
                                savingAlias = true
                                scope.launch { repository.saveEmployee(employee.copy(alias = alias.trim())); savingAlias = false; Toast.makeText(context, "Αποθηκεύτηκε το ψευδώνυμο", Toast.LENGTH_SHORT).show() }
                            }) { Text("Αποθήκευση") }
                        }
                    }
                }
            }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { years.forEach { candidate -> FilterChip(selected = candidate == year, onClick = { year = candidate }, label = { Text(candidate.toString()) }) } } }
            item {
                Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.secondaryContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Σύνολα $year", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Text("Πληρωτέα: ${annualPayroll.asMoney()}", style = MaterialTheme.typography.bodyMedium)
                        Text("Κόστος ενσήμων: ${annualCost.asMoney()}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold, color = BrandGreen)
                        if (loading) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(end = 8.dp), strokeWidth = 2.dp); Text("Υπολογισμός από τις μισθοδοτικές καταστάσεις…", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            months.forEach { (month, monthRows) ->
                val cost = monthRows.map { it.driveFileId to it.source }.distinct().sumOf { (id, _) -> costByFile[id] ?: 0.0 }
                item(key = "employee-month-$year-$month") {
                    Card(colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            Text("${month.toString().padStart(2, '0')}/$year", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Πληρωτέα: ${monthRows.sumOf { it.amount }.asMoney()}", style = MaterialTheme.typography.bodyMedium)
                            Text("Κόστος ενσήμων: ${cost.asMoney()}", style = MaterialTheme.typography.bodyMedium, color = BrandGreen)
                            monthRows.sortedBy { it.kind.ordinal }.forEach { row -> Text("• ${row.kind.label}: ${row.amount.asMoney()}", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            if (months.isEmpty()) item { Text("Δεν υπάρχουν μισθοδοτικές εγγραφές για το $year.") }
        }
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
