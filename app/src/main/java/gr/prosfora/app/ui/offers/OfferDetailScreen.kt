package gr.prosfora.app.ui.offers

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.data.db.SpaceEntity
import gr.prosfora.app.notify.ContactNotifier
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.asNumber
import gr.prosfora.app.util.asOfferDate
import gr.prosfora.app.util.asSentStamp
import gr.prosfora.app.util.parseDecimal
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfferDetailScreen(
    viewModel: OffersViewModel,
    onBack: () -> Unit,
    onComposeEmail: () -> Unit,
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
                        // Ίδιοι κανόνες με το action «ΑΠΟΣΤΟΛΗ ΠΡΟΣΦΟΡΑΣ» του AppSheet
                        IconButton(enabled = current.canSendEmail, onClick = onComposeEmail) {
                            Icon(
                                Icons.Default.Email,
                                contentDescription = "Αποστολή προσφοράς",
                                tint = if (current.canSendEmail) EmailAmber else LocalContentColor.current,
                            )
                        }
                        IconButton(onClick = { viewModel.deleteOffer(current.offer.id); onBack() }) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Διαγραφή",
                                tint = DeleteRed,
                            )
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
            item { NotifyCard(current, viewModel) }
            // Η κατάσταση είναι το τελευταίο βήμα της ροής, οπότε μπαίνει τελευταία
            item { StatusCard(current, viewModel) }
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
                placeholder = { Text("π.χ. Διαμέρισμα") },
                singleLine = true,
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

            HorizontalDivider()
            Text("Στοιχεία πελάτη", style = MaterialTheme.typography.titleSmall)

            OutlinedTextField(
                value = offer.customerName,
                onValueChange = { viewModel.updateOffer(offer.copy(customerName = it)) },
                label = { Text("Ονοματεπώνυμο") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = offer.email,
                onValueChange = { viewModel.updateOffer(offer.copy(email = it)) },
                label = { Text("Email") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = offer.customerPhone,
                onValueChange = { viewModel.updateOffer(offer.copy(customerPhone = it)) },
                label = { Text("Κινητό") },
                placeholder = { Text("π.χ. 6941234567") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------- χώροι ------

@Composable
private fun SpacesCard(details: OfferWithDetails, viewModel: OffersViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ανάλυση χώρων", style = MaterialTheme.typography.titleMedium)
            Text(
                "Πάτα σε μια γραμμή για να την αλλάξεις",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(Modifier.fillMaxWidth()) {
                TableHeader("ΠΕΡΙΓΡΑΦΗ", 2.2f)
                TableHeader("ΕΠΙΦ.", 1f, TextAlign.End)
                TableHeader("ΤΙΜΗ", 1.2f, TextAlign.End)
                TableHeader("ΣΥΝΟΛΟ", 1.3f, TextAlign.End)
                Spacer(Modifier.width(48.dp))
            }
            HorizontalDivider()

            details.spaces.forEach { space ->
                SpaceRow(
                    space = space,
                    onSave = { viewModel.updateSpace(it) },
                    onDelete = { viewModel.deleteSpace(space) },
                )
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

/** Γραμμή χώρου· με tap ανοίγει σε φόρμα επεξεργασίας στη θέση της. */
@Composable
private fun SpaceRow(
    space: SpaceEntity,
    onSave: (SpaceEntity) -> Unit,
    onDelete: () -> Unit,
) {
    var editing by remember(space.id) { mutableStateOf(false) }

    if (!editing) {
        Row(
            Modifier.fillMaxWidth().clickable { editing = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
            IconButton(onClick = { editing = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Edit, contentDescription = "Επεξεργασία", tint = EditBlue)
            }
        }
        return
    }

    var description by remember(space.id) { mutableStateOf(space.description) }
    var area by remember(space.id) { mutableStateOf(space.area.asNumber()) }
    var price by remember(space.id) { mutableStateOf(space.unitPrice.asNumber()) }
    val areaValue = area.parseDecimal()
    val priceValue = price.parseDecimal()
    val valid = description.isNotBlank() && areaValue != null && priceValue != null

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
            if (valid) {
                Text(
                    "Σύνολο γραμμής: ${(areaValue!! * priceValue!!).asMoney()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = valid,
                    onClick = {
                        onSave(
                            space.copy(
                                description = description.trim(),
                                area = areaValue ?: space.area,
                                unitPrice = priceValue ?: space.unitPrice,
                            ),
                        )
                        editing = false
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Αποθήκευση") }

                OutlinedButton(
                    onClick = { editing = false },
                    modifier = Modifier.weight(1f),
                ) { Text("Άκυρο") }

                IconButton(onClick = { editing = false; onDelete() }) {
                    Icon(Icons.Default.Delete, contentDescription = "Διαγραφή", tint = DeleteRed)
                }
            }
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
        Button(
            onClick = {
                onAdd(description.trim(), areaValue ?: 0.0, priceValue ?: 0.0)
                description = ""
                area = ""
                price = ""
            },
            enabled = canAdd,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Προσθήκη χώρου")
        }
    }
}

// ------------------------------------------------------------ σημειώσεις -----

@Composable
private fun NotesCard(
    details: OfferWithDetails,
    presets: List<String>,
    selected: Set<String>,
    viewModel: OffersViewModel,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Παρατηρήσεις", style = MaterialTheme.typography.titleMedium)
            Text(
                "Τσέκαρε όσες ισχύουν — μπαίνουν αυτούσιες στο PDF",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )

            presets.forEach { text ->
                val checked = text in selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleNote(details.offer.id, text, !checked) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { viewModel.toggleNote(details.offer.id, text, it) },
                    )
                    Text(
                        text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                }
            }

            // Σημειώσεις γραμμένες ελεύθερα, που δεν είναι στη βιβλιοθήκη
            val extras = details.notes.filter { it.text !in presets }
            extras.forEach { note ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = true, onCheckedChange = { viewModel.deleteNote(note) })
                    Text(
                        note.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            FreeNoteInput { text, pin -> viewModel.addFreeNote(details.offer.id, text, pin) }
        }
    }
}

@Composable
private fun FreeNoteInput(onAdd: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Νέα σημείωση") },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().clickable { pin = !pin },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = pin, onCheckedChange = { pin = it })
            Text(
                "Κράτησέ την για επόμενες προσφορές",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        // Πλήρες πλάτος: μέσα σε Row με weight το κείμενο στριμωχνόταν και
        // τυπωνόταν κάθετα, ένα γράμμα ανά γραμμή.
        Button(
            onClick = {
                onAdd(text.trim(), pin)
                text = ""
                pin = false
            },
            enabled = text.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Προσθήκη σημείωσης")
        }
    }
}

// ------------------------------------------------------------ ειδοποίηση -----

@Composable
private fun NotifyCard(details: OfferWithDetails, viewModel: OffersViewModel) {
    val context = LocalContext.current
    val offer = details.offer

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ειδοποίηση πελάτη", style = MaterialTheme.typography.titleMedium)
            Text(
                if (details.canNotify) {
                    ContactNotifier.message(details)
                } else if (offer.customerPhone.isBlank()) {
                    "Συμπλήρωσε κινητό για να μπορείς να στείλεις ειδοποίηση."
                } else {
                    "Στείλε πρώτα το email — η ειδοποίηση αναφέρεται σε αυτό."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ContactNotifier.Channel.entries.forEach { channel ->
                    OutlinedButton(
                        enabled = details.canNotify,
                        onClick = {
                            val opened = ContactNotifier.open(context, channel, details)
                            if (opened) {
                                viewModel.markNotified(offer.id, channel.storedValue)
                            } else {
                                Toast.makeText(
                                    context,
                                    "Δεν βρέθηκε εφαρμογή για ${channel.label}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            if (channel == ContactNotifier.Channel.SMS) Icons.Default.Sms else Icons.Default.Send,
                            contentDescription = null,
                            tint = if (channel == ContactNotifier.Channel.SMS) SmsBlue else ViberPurple,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(channel.label)
                    }
                }
            }

            offer.notifiedAt?.let {
                Text(
                    "Στάλθηκε ${offer.notifiedVia.orEmpty()} · ${it.asSentStamp()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = SentGreen,
                )
            }
        }
    }
}

// ------------------------------------------------------------- κατάσταση -----

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun StatusCard(details: OfferWithDetails, viewModel: OffersViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Κατάσταση", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                details.availableStatuses.forEach { status ->
                    FilterChip(
                        selected = details.offer.status == status,
                        onClick = { viewModel.setStatus(details, status) },
                        label = { Text(status.label) },
                        leadingIcon = { StatusDot(status) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = statusColor(status).copy(alpha = 0.20f),
                        ),
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
}

private fun parseGreekDate(text: String): LocalDate? {
    val parts = text.trim().split("/", "-", ".")
    if (parts.size != 3) return null
    val day = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val year = parts[2].toIntOrNull() ?: return null
    return runCatching { LocalDate.of(year, month, day) }.getOrNull()
}
