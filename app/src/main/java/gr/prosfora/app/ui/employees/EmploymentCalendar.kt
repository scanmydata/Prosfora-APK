package gr.prosfora.app.ui.employees

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import gr.prosfora.app.data.db.EmployeeEntity
import gr.prosfora.app.data.db.EmploymentPeriod
import gr.prosfora.app.data.db.EmploymentPeriods
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

private val Active = Color(0xFF00E2A2)
private val OnActive = Color(0xFF00382A)

private val MONTHS = listOf(
    "Ιανουάριος", "Φεβρουάριος", "Μάρτιος", "Απρίλιος", "Μάιος", "Ιούνιος",
    "Ιούλιος", "Αύγουστος", "Σεπτέμβριος", "Οκτώβριος", "Νοέμβριος", "Δεκέμβριος",
)
private val WEEKDAYS = listOf("Δε", "Τρ", "Τε", "Πε", "Πα", "Σα", "Κυ")

private fun LocalDate.label(): String = "%02d/%02d/%04d".format(dayOfMonth, monthValue, year)

/**
 * Το ημερολόγιο απασχόλησης ενός εργαζόμενου.
 *
 * Οι μέρες που δούλευε είναι χρωματισμένες· όσες έμεινε σε παύση, όχι. Ο ίδιος
 * άνθρωπος μπορεί να φύγει και να ξαναέρθει, οπότε οι χρωματιστές μέρες
 * σχηματίζουν όσα διαστήματα χρειαστεί.
 *
 * Αλλαγή: πάτα την πρώτη και την τελευταία μέρα —ακόμη και σε άλλον μήνα— και
 * διάλεξε «Ενεργός» ή «Παύση». Οι αλλαγές μένουν στην οθόνη ως την αποθήκευση.
 *
 * Αν δεν έχει οριστεί τίποτα ακόμη, το ημερολόγιο ανοίγει με πρόταση από τις
 * μισθοδοσίες: κάθε μήνας με μισθοδοσία ενεργός, κάθε κενό παύση.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmploymentCalendarScreen(
    employee: EmployeeEntity,
    payrollMonths: List<YearMonth>,
    onSave: (List<EmploymentPeriod>) -> Unit,
    onBack: () -> Unit,
) {
    val today = remember { LocalDate.now() }
    val stored = remember(employee.periods) { EmploymentPeriods.parse(employee.periods) }
    val suggested = remember(employee.id) {
        if (stored.isNotEmpty()) {
            emptyList()
        } else {
            EmploymentPeriods.suggestFromPayroll(payrollMonths, employee.leftDay?.let(LocalDate::ofEpochDay), today)
        }
    }
    val initial = stored.ifEmpty { suggested }

    var periods by remember(employee.id, employee.periods) { mutableStateOf(initial) }
    var month by remember(employee.id) {
        mutableStateOf(YearMonth.from(initial.lastOrNull()?.let { it.end ?: today } ?: today))
    }
    var anchor by remember { mutableStateOf<LocalDate?>(null) }
    var range by remember { mutableStateOf<Pair<LocalDate, LocalDate>?>(null) }
    var picking by remember { mutableStateOf<DatePurpose?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }

    // Αποθηκευμένο = ό,τι γράφτηκε. Μια πρόταση από τις μισθοδοσίες δεν έχει
    // γραφτεί ακόμη, οπότε μετράει ως αλλαγή που περιμένει αποθήκευση.
    val dirty = periods != stored
    val leave: () -> Unit = {
        if (dirty) {
            confirmLeave = true
        } else {
            onBack()
        }
    }
    BackHandler(onBack = leave)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Ημερολόγιο απασχόλησης", fontWeight = FontWeight.Bold)
                        Text(employee.display, style = MaterialTheme.typography.labelSmall)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = leave) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Πίσω")
                    }
                },
                actions = {
                    TextButton(enabled = dirty, onClick = { onSave(periods) }) {
                        Text("Αποθήκευση", color = if (dirty) Active else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Summary(periods, today)

            if (stored.isEmpty() && suggested.isNotEmpty()) {
                Text(
                    "Πρόταση από τις μισθοδοσίες: κάθε μήνας με μισθοδοσία ενεργός, κάθε κενό " +
                        "παύση. Έλεγξέ την και πάτα Αποθήκευση.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Card(
                colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surfaceVariant),
                shape = RoundedCornerShape(16.dp),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { month = month.minusMonths(1) }) {
                            Icon(Icons.Default.ChevronLeft, contentDescription = "Προηγούμενος μήνας")
                        }
                        Text(
                            "${MONTHS[month.monthValue - 1]} ${month.year}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { month = month.plusMonths(1) }) {
                            Icon(Icons.Default.ChevronRight, contentDescription = "Επόμενος μήνας")
                        }
                    }
                    MonthGrid(
                        month = month,
                        periods = periods,
                        anchor = anchor,
                        today = today,
                        onDay = { day ->
                            val first = anchor
                            if (first == null) {
                                anchor = day
                            } else {
                                range = minOf(first, day) to maxOf(first, day)
                                anchor = null
                            }
                        },
                    )
                    Text(
                        anchor?.let { "Από ${it.label()} — πάτα την τελευταία μέρα (και σε άλλον μήνα)" }
                            ?: "Πάτα την πρώτη και την τελευταία μέρα ενός διαστήματος",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { picking = DatePurpose.LEAVE },
                    enabled = periods.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Αποχώρηση / διακοπή") }
                OutlinedButton(
                    onClick = { picking = DatePurpose.REHIRE },
                    modifier = Modifier.weight(1f),
                ) { Text(if (periods.isEmpty()) "Πρόσληψη" else "Επαναπρόσληψη") }
            }

            if (periods.isNotEmpty()) {
                Text("ΠΕΡΙΟΔΟΙ", style = MaterialTheme.typography.titleSmall, color = Active, fontWeight = FontWeight.Bold)
                periods.asReversed().forEach { period ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { month = YearMonth.from(period.start) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(Active))
                        Spacer(Modifier.size(10.dp))
                        Text(
                            "${period.start.label()} — ${period.end?.label() ?: "σήμερα"}",
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${EmploymentPeriods.workedDays(listOf(period), today)} ημ.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    range?.let { (from, to) ->
        AlertDialog(
            onDismissRequest = { range = null },
            title = { Text(if (from == to) from.label() else "${from.label()} — ${to.label()}") },
            text = { Text("Τι ισχύει για αυτό το διάστημα;") },
            confirmButton = {
                TextButton(onClick = {
                    periods = EmploymentPeriods.mark(periods, from, to)
                    range = null
                }) { Text("Ενεργός", color = Active) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { range = null }) { Text("Άκυρο") }
                    TextButton(onClick = {
                        periods = EmploymentPeriods.unmark(periods, from, to)
                        range = null
                    }) { Text("Παύση") }
                }
            },
        )
    }

    picking?.let { purpose ->
        val state = rememberDatePickerState(initialSelectedDateMillis = today.toEpochDay() * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    // Ο επιλογέας δουλεύει σε UTC· η μετατροπή γίνεται εκεί,
                    // αλλιώς η τοπική ζώνη μετακινεί τη μέρα κατά μία
                    state.selectedDateMillis?.let { millis ->
                        val day = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                        periods = when (purpose) {
                            DatePurpose.LEAVE -> EmploymentPeriods.withLeftDay(periods, day)
                            DatePurpose.REHIRE -> EmploymentPeriods.rehire(periods, day)
                        }
                        month = YearMonth.from(day)
                    }
                    picking = null
                }) { Text(if (purpose == DatePurpose.LEAVE) "Τελευταία μέρα" else "Πρώτη μέρα", color = Active) }
            },
            dismissButton = { TextButton(onClick = { picking = null }) { Text("Άκυρο") } },
        ) { DatePicker(state = state) }
    }

    if (confirmLeave) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("Αποθήκευση αλλαγών;") },
            text = { Text("Οι αλλαγές στο ημερολόγιο δεν έχουν αποθηκευτεί.") },
            confirmButton = {
                TextButton(onClick = { confirmLeave = false; onSave(periods) }) { Text("Αποθήκευση", color = Active) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLeave = false; onBack() }) { Text("Απόρριψη") }
            },
        )
    }
}

private enum class DatePurpose { LEAVE, REHIRE }

@Composable
private fun Summary(periods: List<EmploymentPeriod>, today: LocalDate) {
    val status = when {
        periods.isEmpty() -> "Δεν έχουν οριστεί περίοδοι απασχόλησης"
        EmploymentPeriods.isActive(periods, today) -> {
            val current = periods.last { it.contains(today) }
            "Ενεργός από ${current.start.label()}" + (current.end?.let { " ως ${it.label()}" } ?: "")
        }
        periods.last().start.isAfter(today) -> "Ξεκινά ${periods.last().start.label()}"
        else -> "Σε παύση από ${periods.last { !it.start.isAfter(today) }.end?.plusDays(1)?.label() ?: "—"}"
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(status, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (periods.isNotEmpty()) {
            Text(
                "${EmploymentPeriods.workedDays(periods, today)} ημέρες σε ${periods.size} " +
                    if (periods.size == 1) "περίοδο" else "περιόδους",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    periods: List<EmploymentPeriod>,
    anchor: LocalDate?,
    today: LocalDate,
    onDay: (LocalDate) -> Unit,
) {
    // Η εβδομάδα αρχίζει Δευτέρα, όπως στα ελληνικά ημερολόγια
    val lead = month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value
    val cells: List<LocalDate?> = List(lead) { null } + (1..month.lengthOfMonth()).map(month::atDay)
    val weeks = cells.chunked(7)

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row {
            WEEKDAYS.forEach { name ->
                Text(
                    name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        weeks.forEach { week ->
            Row {
                week.forEach { day -> DayCell(day, periods, anchor, today, onDay, Modifier.weight(1f)) }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: LocalDate?,
    periods: List<EmploymentPeriod>,
    anchor: LocalDate?,
    today: LocalDate,
    onDay: (LocalDate) -> Unit,
    modifier: Modifier,
) {
    Box(modifier.aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
        if (day == null) return@Box
        val active = EmploymentPeriods.isActive(periods, day)
        val border = when {
            day == anchor -> BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            day == today -> BorderStroke(1.dp, MaterialTheme.colorScheme.onSurface)
            else -> null
        }
        Box(
            Modifier
                .fillMaxSize()
                .clip(CircleShape)
                .background(if (active) Active else Color.Transparent)
                .let { if (border != null) it.border(border, CircleShape) else it }
                .clickable { onDay(day) },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                day.dayOfMonth.toString(),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (active || day == today) FontWeight.Bold else FontWeight.Normal,
                color = if (active) OnActive else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
