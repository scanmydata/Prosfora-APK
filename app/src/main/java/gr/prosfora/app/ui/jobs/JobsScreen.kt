package gr.prosfora.app.ui.jobs

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gr.prosfora.app.data.db.JobStage
import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.ui.offers.OffersViewModel
import gr.prosfora.app.ui.offers.SentGreen
import gr.prosfora.app.ui.offers.statusColor
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.asOfferDate
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Οι τρέχουσες εργασίες: ποιες ολοκληρωμένες προσφορές έγιναν έργα, πότε
 * ξεκίνησαν και πότε τελείωσαν.
 *
 * Μόνο οι **ολοκληρωμένες** προσφορές εμφανίζονται εδώ — μια προσφορά που δεν
 * έχει κλείσει δεν είναι δουλειά.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JobsScreen(
    viewModel: OffersViewModel,
    onMenu: () -> Unit,
    onRequestReview: (String) -> Unit,
) {
    val context = LocalContext.current
    val settings = remember { GoogleSettings(context) }
    val offers by viewModel.offers.collectAsState()

    val jobs = remember(offers) { offers.filter { it.jobStage != JobStage.NOT_A_JOB } }
    val pending = jobs.filter { it.jobStage == JobStage.PENDING }
    val running = jobs.filter { it.jobStage == JobStage.IN_PROGRESS }
    val finished = jobs.filter { it.jobStage == JobStage.FINISHED }
        .sortedByDescending { it.offer.workEndDay ?: 0 }

    var picking by remember { mutableStateOf<DateTarget?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Εργασίες") },
                navigationIcon = { gr.prosfora.app.ui.MenuButton(onMenu) },
            )
        },
    ) { padding ->
        if (jobs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "Δεν υπάρχουν ολοκληρωμένες προσφορές ακόμη",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            section("Σε εξέλιξη", running, settings, viewModel, { picking = it }, onRequestReview)
            section("Χωρίς έναρξη", pending, settings, viewModel, { picking = it }, onRequestReview)
            section("Ολοκληρωμένες", finished, settings, viewModel, { picking = it }, onRequestReview)
        }
    }

    picking?.let { target ->
        val initial = target.currentDay ?: LocalDate.now().toEpochDay()
        val state = rememberDatePickerState(
            initialSelectedDateMillis = initial * 86_400_000L,
        )
        DatePickerDialog(
            onDismissRequest = { picking = null },
            confirmButton = {
                TextButton(onClick = {
                    val millis = state.selectedDateMillis
                    if (millis != null) {
                        // Ο DatePicker δουλεύει σε UTC· μετατροπή σε epoch day χωρίς
                        // να παρέμβει η τοπική ζώνη και μετακινήσει τη μέρα.
                        val day = java.time.Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                        if (target.isStart) {
                            viewModel.setWorkStart(target.details.offer, day)
                        } else {
                            viewModel.setWorkEnd(target.details.offer, day)
                        }
                    }
                    picking = null
                }) { Text("Επιλογή") }
            },
            dismissButton = {
                TextButton(onClick = { picking = null }) { Text("Άκυρο") }
            },
        ) {
            DatePicker(state = state)
        }
    }
}

private data class DateTarget(
    val details: OfferWithDetails,
    val isStart: Boolean,
    val currentDay: Long?,
)

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String,
    items: List<OfferWithDetails>,
    settings: GoogleSettings,
    viewModel: OffersViewModel,
    onPickDate: (DateTarget) -> Unit,
    onRequestReview: (String) -> Unit,
) {
    if (items.isEmpty()) return
    item(key = "header-$title") {
        Text(
            "$title  ·  ${items.size}",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
    }
    items(items, key = { it.offer.id }) { details ->
        JobCard(details, settings, viewModel, onPickDate, onRequestReview)
    }
}

@Composable
private fun JobCard(
    details: OfferWithDetails,
    settings: GoogleSettings,
    viewModel: OffersViewModel,
    onPickDate: (DateTarget) -> Unit,
    onRequestReview: (String) -> Unit,
) {
    val offer = details.offer
    val delay = settings.reviewDelayDays
    val reviewDue = details.reviewDue(delay)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                offer.address.ifBlank { "(χωρίς διεύθυνση)" },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "${offer.kind} · ${details.grandTotal.asMoney()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = { onPickDate(DateTarget(details, true, offer.workStartDay)) },
                    label = {
                        Text(
                            offer.workStartDay?.let { "Έναρξη ${it.asOfferDate()}" } ?: "Έναρξη",
                            maxLines = 1,
                        )
                    },
                    leadingIcon = {
                        Icon(Icons.Default.PlayArrow, null, Modifier.size(16.dp), tint = statusColor(offer.status))
                    },
                )
                AssistChip(
                    onClick = { onPickDate(DateTarget(details, false, offer.workEndDay)) },
                    enabled = offer.workStartDay != null,
                    label = {
                        Text(
                            offer.workEndDay?.let { "Τέλος ${it.asOfferDate()}" } ?: "Ολοκλήρωση",
                            maxLines = 1,
                        )
                    },
                )
            }

            when {
                offer.reviewSentAt != null -> Text(
                    "★ Στάλθηκε αίτημα αξιολόγησης",
                    style = MaterialTheme.typography.bodySmall,
                    color = SentGreen,
                )

                reviewDue -> Button(
                    onClick = { onRequestReview(offer.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Star, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Αίτημα αξιολόγησης", maxLines = 1)
                }

                offer.workEndDay != null -> {
                    val remaining = delay - (details.daysSinceFinish() ?: 0)
                    Text(
                        "Το αίτημα αξιολόγησης ενεργοποιείται σε $remaining ${if (remaining == 1L) "μέρα" else "μέρες"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = { onRequestReview(offer.id) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Αποστολή τώρα") }
                }
            }
        }
    }
}
