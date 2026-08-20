package gr.prosfora.app.ui.offers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import gr.prosfora.app.data.db.OfferStatus
import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.data.db.SpaceEntity
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.asNumber
import gr.prosfora.app.util.asOfferDate
import gr.prosfora.app.util.parseDecimal
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfferDetailScreen(
    viewModel: OffersViewModel,
    onBack: () -> Unit,
) {
    val details by viewModel.selectedOffer.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val selectedNotes by viewModel.selectedNoteTexts.collectAsState()
    val current = details

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current?.offer?.address?.ifBlank { "Νέα προσφορά" } ?: "Προσφορά") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Πίσω")
                    }
                },
                actions = {
                    if (current != null) {
                        IconButton(onClick = { viewModel.deleteOffer(current.offer.id); onBack() }) {
                            Icon(Icons.Default.Delete, contentDescription = "Διαγραφή")
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (current == null) return@Scaffold

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { HeaderCard(current, viewModel) }
            item { SpacesCard(current, viewModel) }
            item { NotesCard(current, presets.map { it.text }, selectedNotes, viewModel) }
        }
    }
}

@Composable
private fun HeaderCard(details: OfferWithDetails, viewModel: OffersViewModel) {
    val offer = details.offer
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = offer.address,
                onValueChange = { viewModel.updateOffer(offer.copy(address = it)) },
                label = { Text("Οδός / Περιοχή") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = offer.kind,
                onValueChange = { viewModel.updateOffer(offer.copy(kind = it)) },
                label = { Text("Είδος") },
                placeholder = { Text("π.χ. Χρωματισμός διαμερίσματος") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = offer.email,
                onValueChange = { viewModel.updateOffer(offer.copy(email = it)) },
                label = { Text("Email πελάτη") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )

            var dateText by remember(offer.id, offer.dateEpochDay) {
                mutableStateOf(offer.dateEpochDay.asOfferDate())
            }
            OutlinedTextField(
                value = dateText,
                onValueChange = { text ->
                    dateText = text
                    parseGreekDate(text)?.let {
                        viewModel.updateOffer(offer.copy(dateEpochDay = it.toEpochDay()))
                    }
                },
                label = { Text("Ημερομηνία") },
                placeholder = { Text("π.χ. 20/8/2026") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            StatusSelector(details, viewModel)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun StatusSelector(details: OfferWithDetails, viewModel: OffersViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Κατάσταση", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            details.availableStatuses.forEach { status ->
                FilterChip(
                    selected = details.offer.status == status,
                    onClick = { viewModel.setStatus(details, status) },
                    label = { Text(status.label) },
                    leadingIcon = { StatusDot(status) },
                )
            }
        }
        if (details.spaces.isEmpty()) {
            Text(
                "Πρόσθεσε χώρους για να ξεκλειδώσει «Σε επεξεργασία» / «Ολοκληρώθηκε»",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SpacesCard(details: OfferWithDetails, viewModel: OffersViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ανάλυση χώρων", style = MaterialTheme.typography.titleMedium)

            Row(Modifier.fillMaxWidth()) {
                TableHeader("ΠΕΡΙΓΡΑΦΗ", 2.2f)
                TableHeader("ΕΠΙΦ.", 1f, TextAlign.End)
                TableHeader("ΤΙΜΗ", 1.2f, TextAlign.End)
                TableHeader("ΣΥΝΟΛΟ", 1.3f, TextAlign.End)
                androidx.compose.foundation.layout.Spacer(Modifier.width(48.dp))
            }
            HorizontalDivider()

            details.spaces.forEach { space ->
                SpaceRow(space, onDelete = { viewModel.deleteSpace(space) })
            }

            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ΣΥΝΟΛΟ",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    details.total.asMoney(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            AddSpaceRow { description, area, price ->
                viewModel.addSpace(details.offer.id, description, area, price)
            }
        }
    }
}

@Composable
private fun RowScope.TableHeader(text: String, weight: Float, align: TextAlign = TextAlign.Start) {
    Text(
        text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = align,
    )
}

@Composable
private fun SpaceRow(space: SpaceEntity, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(space.description, Modifier.weight(2.2f), style = MaterialTheme.typography.bodyMedium)
        Text(
            space.area.asNumber(),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
        Text(
            space.unitPrice.asMoney(),
            Modifier.weight(1.2f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
        )
        Text(
            space.lineTotal.asMoney(),
            Modifier.weight(1.3f),
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.End,
            fontWeight = FontWeight.Medium,
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Διαγραφή χώρου")
        }
    }
}

@Composable
private fun AddSpaceRow(onAdd: (String, Double, Double) -> Unit) {
    var description by remember { mutableStateOf("") }
    var area by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }

    val areaValue = area.parseDecimal()
    val priceValue = price.parseDecimal()
    val canAdd = description.isNotBlank() && areaValue != null && priceValue != null

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Περιγραφή χώρου") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = area,
                onValueChange = { area = it },
                label = { Text("Επιφάνεια") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = price,
                onValueChange = { price = it },
                label = { Text("Τιμή μονάδος") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }
        if (canAdd) {
            Text(
                "Σύνολο γραμμής: ${(areaValue!! * priceValue!!).asMoney()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = {
                onAdd(description.trim(), areaValue ?: 0.0, priceValue ?: 0.0)
                description = ""
                area = ""
                price = ""
            },
            enabled = canAdd,
        ) {
            Text("+ Προσθήκη χώρου")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun NotesCard(
    details: OfferWithDetails,
    presets: List<String>,
    selected: Set<String>,
    viewModel: OffersViewModel,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Παρατηρήσεις", style = MaterialTheme.typography.titleMedium)
            Text(
                "Τσέκαρε όσες ισχύουν — μπαίνουν αυτούσιες στο PDF",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            presets.forEach { text ->
                FilterChip(
                    selected = text in selected,
                    onClick = {
                        viewModel.toggleNote(details.offer.id, text, text !in selected)
                    },
                    label = { Text(text, style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            // Σημειώσεις που γράφτηκαν ελεύθερα και δεν είναι στη βιβλιοθήκη
            val extras = details.notes.filter { it.text !in presets }
            extras.forEach { note ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "• ${note.text}",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    IconButton(onClick = { viewModel.deleteNote(note) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Διαγραφή σημείωσης")
                    }
                }
            }

            FreeNoteInput { text, pin -> viewModel.addFreeNote(details.offer.id, text, pin) }
        }
    }
}

@Composable
private fun FreeNoteInput(onAdd: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Νέα σημείωση") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Checkbox(checked = pin, onCheckedChange = { pin = it })
            Text(
                "Κράτησέ την για επόμενες προσφορές",
                style = MaterialTheme.typography.bodySmall,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            TextButton(
                onClick = {
                    onAdd(text.trim(), pin)
                    text = ""
                    pin = false
                },
                enabled = text.isNotBlank(),
            ) {
                Text("Προσθήκη")
            }
        }
    }
}

private fun parseGreekDate(text: String): LocalDate? {
    val parts = text.trim().split("/", "-", ".")
    if (parts.size != 3) return null
    val day = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val year = parts[2].toIntOrNull() ?: return null
    return runCatching { LocalDate.of(year, month, day) }.getOrNull()
}
