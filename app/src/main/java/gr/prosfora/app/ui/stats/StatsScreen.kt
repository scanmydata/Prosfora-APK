package gr.prosfora.app.ui.stats

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gr.prosfora.app.data.db.OfferStatus
import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.ui.offers.EditBlue
import gr.prosfora.app.ui.offers.EmailAmber
import gr.prosfora.app.ui.offers.OffersViewModel
import gr.prosfora.app.ui.offers.SentGreen
import gr.prosfora.app.util.asMoney
import java.time.LocalDate

private val MONTHS = listOf(
    "Ιαν", "Φεβ", "Μαρ", "Απρ", "Μαΐ", "Ιουν",
    "Ιουλ", "Αυγ", "Σεπ", "Οκτ", "Νοε", "Δεκ",
)

/**
 * Στατιστικά ανά μήνα και έτος: προσφορές, τζίρος, δουλειές που ξεκίνησαν και
 * ολοκληρώθηκαν. Τα διαγράμματα σχεδιάζονται σε Canvas — καμία εξωτερική
 * βιβλιοθήκη γραφημάτων.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(viewModel: OffersViewModel, onMenu: () -> Unit) {
    val offers by viewModel.offers.collectAsState()
    val context = LocalContext.current
    val settings = remember { GoogleSettings(context) }

    var includeImported by remember { mutableStateOf(settings.statsIncludeImported) }
    val importedCount = remember(offers) { offers.count { it.imported } }

    val visible = remember(offers, includeImported) {
        if (includeImported) offers else offers.filter { !it.imported }
    }

    val years = remember(visible) {
        visible.map { LocalDate.ofEpochDay(it.offer.dateEpochDay).year }
            .distinct()
            .sortedDescending()
            .ifEmpty { listOf(LocalDate.now().year) }
    }
    var year by remember(years) { mutableStateOf(years.first()) }

    val stats = remember(visible, year) { YearStats.from(visible, year) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Στατιστικά") },
                navigationIcon = { gr.prosfora.app.ui.MenuButton(onMenu) },
                actions = { gr.prosfora.app.ui.offers.UpdateAction() },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                // Πολλά έτη: η σειρά κυλάει οριζόντια αντί να στριμωχτούν
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

            if (importedCount > 0) {
                item {
                    ImportedToggle(
                        included = includeImported,
                        count = importedCount,
                        onChange = {
                            includeImported = it
                            settings.statsIncludeImported = it
                        },
                    )
                }
            }

            item { SummaryCard(stats) }

            item {
                ChartCard(
                    title = "Προσφορές ανά μήνα",
                    series = listOf(
                        Series("Σύνολο", stats.offersPerMonth, EditBlue),
                        Series("Ολοκληρωμένες", stats.completedPerMonth, SentGreen),
                    ),
                )
            }

            item {
                ChartCard(
                    title = "Εργασίες ανά μήνα",
                    series = listOf(
                        Series("Έναρξη", stats.startedPerMonth, EmailAmber),
                        Series("Ολοκλήρωση", stats.finishedPerMonth, SentGreen),
                    ),
                )
            }

            // Ο τζίρος τελευταίος: είναι το συμπέρασμα, όχι η αφετηρία
            item {
                ChartCard(
                    title = "Τζίρος ανά μήνα — καθαρή αξία (€)",
                    series = listOf(Series("Ευρώ", stats.revenuePerMonth, EmailAmber)),
                    valueLabel = { it.toDouble().asMoney() },
                    footnote = "Οι τιμές των προσφορών είναι καθαρή αξία· ο ΦΠΑ δεν περιλαμβάνεται.",
                )
            }
        }
    }
}

/**
 * Οι εισαγόμενες προσφορές είναι παλιές επιμετρήσεις: δεν ξέρουμε σίγουρα ποιες
 * έγιναν δουλειές, οπότε ο τζίρος τους είναι ένδειξη και όχι απολογισμός. Ο
 * διακόπτης τις βγάζει από τους υπολογισμούς χωρίς να τις σβήσει από πουθενά.
 */
@Composable
private fun ImportedToggle(included: Boolean, count: Int, onChange: (Boolean) -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Ιστορικό από Excel", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (included) {
                        "$count παλιές προσφορές μετράνε στους αριθμούς"
                    } else {
                        "$count παλιές προσφορές μένουν έξω"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = included, onCheckedChange = onChange)
        }
    }
}

private data class Series(val label: String, val values: List<Float>, val color: Color)

private class YearStats(
    val offersPerMonth: List<Float>,
    val completedPerMonth: List<Float>,
    val revenuePerMonth: List<Float>,
    val startedPerMonth: List<Float>,
    val finishedPerMonth: List<Float>,
    val totalOffers: Int,
    val totalCompleted: Int,
    val totalRevenue: Double,
    val totalJobsFinished: Int,
) {
    companion object {
        fun from(offers: List<OfferWithDetails>, year: Int): YearStats {
            val offersPer = FloatArray(12)
            val completedPer = FloatArray(12)
            val revenuePer = FloatArray(12)
            val startedPer = FloatArray(12)
            val finishedPer = FloatArray(12)

            offers.forEach { details ->
                val date = LocalDate.ofEpochDay(details.offer.dateEpochDay)
                if (date.year == year) {
                    val index = date.monthValue - 1
                    offersPer[index]++
                    if (details.offer.status == OfferStatus.COMPLETED) {
                        completedPer[index]++
                        revenuePer[index] += details.total.toFloat()
                    }
                }
                details.offer.workStartDay?.let {
                    val day = LocalDate.ofEpochDay(it)
                    if (day.year == year) startedPer[day.monthValue - 1]++
                }
                details.offer.workEndDay?.let {
                    val day = LocalDate.ofEpochDay(it)
                    if (day.year == year) finishedPer[day.monthValue - 1]++
                }
            }

            return YearStats(
                offersPerMonth = offersPer.toList(),
                completedPerMonth = completedPer.toList(),
                revenuePerMonth = revenuePer.toList(),
                startedPerMonth = startedPer.toList(),
                finishedPerMonth = finishedPer.toList(),
                totalOffers = offersPer.sum().toInt(),
                totalCompleted = completedPer.sum().toInt(),
                totalRevenue = revenuePer.sum().toDouble(),
                totalJobsFinished = finishedPer.sum().toInt(),
            )
        }
    }
}

@Composable
private fun SummaryCard(stats: YearStats) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Σύνοψη έτους", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Metric("Προσφορές", stats.totalOffers.toString())
                Metric("Ολοκληρωμένες", stats.totalCompleted.toString())
                Metric("Εργασίες", stats.totalJobsFinished.toString())
            }
            Text(
                "Τζίρος ολοκληρωμένων: ${stats.totalRevenue.asMoney()}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Καθαρή αξία, χωρίς ΦΠΑ",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChartCard(
    title: String,
    series: List<Series>,
    valueLabel: (Float) -> String = { it.toInt().toString() },
    footnote: String? = null,
) {
    val max = series.flatMap { it.values }.maxOrNull() ?: 0f
    val peak = series.flatMap { it.values }.withIndex().maxByOrNull { it.value }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                series.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Canvas(Modifier.size(10.dp)) { drawRect(entry.color) }
                        Text(
                            "  ${entry.label}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (max <= 0f) {
                Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Χωρίς δεδομένα για αυτό το έτος",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                return@Column
            }

            val axisColor = MaterialTheme.colorScheme.outlineVariant
            Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                val groupWidth = size.width / 12f
                val barGap = groupWidth * 0.12f
                val barWidth = (groupWidth - barGap * 2) / series.size
                val chartHeight = size.height - 4f

                drawLine(
                    color = axisColor,
                    start = Offset(0f, chartHeight),
                    end = Offset(size.width, chartHeight),
                    strokeWidth = 2f,
                )

                for (month in 0 until 12) {
                    series.forEachIndexed { seriesIndex, entry ->
                        val value = entry.values[month]
                        if (value <= 0f) return@forEachIndexed
                        val barHeight = (value / max) * (chartHeight - 8f)
                        val left = month * groupWidth + barGap + seriesIndex * barWidth
                        drawRect(
                            color = entry.color,
                            topLeft = Offset(left, chartHeight - barHeight),
                            size = Size(barWidth * 0.85f, barHeight),
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth()) {
                MONTHS.forEach { month ->
                    Text(
                        month.take(1),
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }

            peak?.let {
                val monthIndex = it.index % 12
                Text(
                    "Κορυφή: ${MONTHS[monthIndex]} · ${valueLabel(it.value)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            footnote?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
