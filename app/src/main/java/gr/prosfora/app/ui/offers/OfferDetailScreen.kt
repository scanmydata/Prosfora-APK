package gr.prosfora.app.ui.offers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import gr.prosfora.app.data.db.Gender
import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.data.db.SpaceEntity
import gr.prosfora.app.notify.Channel
import gr.prosfora.app.ui.components.ConfirmDialog
import gr.prosfora.app.ui.components.StableTextField
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.asNumber
import gr.prosfora.app.util.asOfferDate
import gr.prosfora.app.util.asSentStamp
import gr.prosfora.app.util.parseDecimal
import gr.prosfora.app.google.GoogleSettings
import java.time.LocalDate
import java.time.ZoneOffset

/** Πλάτος της στήλης ενεργειών του πίνακα — ίδιο σε επικεφαλίδα και γραμμές. */
private val ACTIONS_WIDTH = 76.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfferDetailScreen(
    viewModel: OffersViewModel,
    onBack: () -> Unit,
    onComposeEmail: () -> Unit,
    onComposeMessage: (Channel) -> Unit,
) {
    val details by viewModel.selectedOffer.collectAsState()
    val presets by viewModel.presets.collectAsState()
    val selectedNotes by viewModel.selectedNoteTexts.collectAsState()
    val current = details

    var confirmDeleteOffer by remember { mutableStateOf(false) }

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
                        SendMenu(current, onComposeEmail, onComposeMessage)
                        IconButton(onClick = { confirmDeleteOffer = true }) {
                            Icon(Icons.Default.Delete, contentDescription = "Διαγραφή", tint = DeleteRed)
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
            item { ExtrasCard(current, viewModel) }
            item { VatCard(current, viewModel) }
            item { NotesCard(current, presets.map { it.text }, selectedNotes, viewModel) }
            item { TermsCard(current, viewModel) }
            if (current.offer.lastSentAt != null || current.offer.notifiedAt != null) {
                item { HistoryCard(current) }
            }
            // Η κατάσταση είναι το τελευταίο βήμα της ροής, οπότε μπαίνει τελευταία
            item { StatusCard(current, viewModel) }
        }

        if (confirmDeleteOffer) {
            ConfirmDialog(
                title = "Διαγραφή προσφοράς",
                message = "Θα διαγραφεί η προσφορά «${current.offer.address.ifBlank { "χωρίς διεύθυνση" }}» " +
                    "μαζί με ${current.spaces.size} χώρους και ${current.notes.size} σημειώσεις. " +
                    "Η διαγραφή συγχρονίζεται και στους υπόλοιπους χρήστες.",
                onConfirm = { viewModel.deleteOffer(current.offer.id); onBack() },
                onDismiss = { confirmDeleteOffer = false },
            )
        }
    }
}

/** Ένα κουμπί αποστολής, τρεις επιλογές: email, SMS, Viber. */
@Composable
private fun SendMenu(
    details: OfferWithDetails,
    onComposeEmail: () -> Unit,
    onComposeMessage: (Channel) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val canEmail = details.canSendEmail
    val hasPhone = details.offer.customerPhone.isNotBlank()

    Box {
        IconButton(enabled = canEmail || hasPhone, onClick = { open = true }) {
            Icon(
                Icons.Default.Send,
                contentDescription = "Αποστολή",
                tint = if (canEmail || hasPhone) EmailAmber else LocalContentColor.current,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Email με το PDF") },
                enabled = canEmail,
                leadingIcon = { Icon(Icons.Default.Email, null, tint = EmailAmber) },
                onClick = { open = false; onComposeEmail() },
            )
            DropdownMenuItem(
                text = { Text("Μήνυμα SMS") },
                enabled = hasPhone,
                leadingIcon = { Icon(Icons.Default.Sms, null, tint = SmsBlue) },
                onClick = { open = false; onComposeMessage(Channel.SMS) },
            )
            DropdownMenuItem(
                text = { Text("Viber") },
                enabled = hasPhone,
                leadingIcon = { Icon(Icons.Default.Send, null, tint = ViberPurple) },
                onClick = { open = false; onComposeMessage(Channel.VIBER) },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeaderCard(details: OfferWithDetails, viewModel: OffersViewModel) {
    val offer = details.offer
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            StableTextField(
                value = offer.address,
                onValueChange = { viewModel.updateOffer(offer.copy(address = it)) },
                label = "Οδός / Περιοχή",
                modifier = Modifier.fillMaxWidth(),
            )
            StableTextField(
                value = offer.kind,
                onValueChange = { viewModel.updateOffer(offer.copy(kind = it)) },
                label = "Είδος",
                placeholder = "π.χ. Διαμέρισμα",
                modifier = Modifier.fillMaxWidth(),
            )
            StableTextField(
                value = offer.dateEpochDay.asOfferDate(),
                onValueChange = { text ->
                    parseGreekDate(text)?.let {
                        viewModel.updateOffer(offer.copy(dateEpochDay = it.toEpochDay()))
                    }
                },
                label = "Ημερομηνία",
                placeholder = "π.χ. 20/8/2026",
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider()
            Text("Στοιχεία πελάτη", style = MaterialTheme.typography.titleSmall)

            StableTextField(
                value = offer.customerName,
                onValueChange = { viewModel.updateOffer(offer.copy(customerName = it)) },
                label = "Όνομα",
                modifier = Modifier.fillMaxWidth(),
            )
            StableTextField(
                value = offer.customerLastName,
                onValueChange = { viewModel.updateOffer(offer.copy(customerLastName = it)) },
                label = "Επώνυμο",
                modifier = Modifier.fillMaxWidth(),
            )

            // Χρειάζεται μόνο για το «κύριε»/«κυρία» όταν ο χαιρετισμός γίνεται
            // με επώνυμο· γι' αυτό και επιτρέπεται να μείνει άγνωστο
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Gender.entries.forEach { option ->
                    FilterChip(
                        selected = offer.customerGender == option,
                        onClick = { viewModel.updateOffer(offer.copy(customerGender = option)) },
                        label = { Text(option.label) },
                    )
                }
            }
            StableTextField(
                value = offer.email,
                onValueChange = { viewModel.updateOffer(offer.copy(email = it)) },
                label = "Email",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            StableTextField(
                value = offer.customerPhone,
                onValueChange = { viewModel.updateOffer(offer.copy(customerPhone = it)) },
                label = "Κινητό",
                placeholder = "π.χ. 6941234567",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

// ---------------------------------------------------------------- χώροι ------

@Composable
private fun SpacesCard(details: OfferWithDetails, viewModel: OffersViewModel) {
    var editingId by remember(details.offer.id) { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<SpaceEntity?>(null) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ανάλυση χώρων", style = MaterialTheme.typography.titleMedium)

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TableHeader("ΠΕΡΙΓΡΑΦΗ", 2.0f)
                TableHeader("ΕΠΙΦ.", 1.0f, TextAlign.End)
                TableHeader("ΤΙΜΗ", 1.2f, TextAlign.End)
                TableHeader("ΣΥΝΟΛΟ", 1.3f, TextAlign.End)
                Spacer(Modifier.width(ACTIONS_WIDTH))
            }
            HorizontalDivider()

            details.spaces.forEach { space ->
                if (editingId == space.id) {
                    SpaceEditor(
                        space = space,
                        onSave = { viewModel.updateSpace(it); editingId = null },
                        onCancel = { editingId = null },
                    )
                } else {
                    SpaceRow(
                        space = space,
                        onEdit = { editingId = space.id },
                        onDelete = { pendingDelete = space },
                    )
                }
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
                    details.linesTotal.asMoney(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End,
                )
                Spacer(Modifier.width(ACTIONS_WIDTH))
            }

            AddSpaceRow { description, area, price ->
                viewModel.addSpace(details.offer.id, description, area, price)
            }
        }
    }

    pendingDelete?.let { space ->
        ConfirmDialog(
            title = "Διαγραφή χώρου",
            message = "Θα διαγραφεί «${space.description}» (${space.lineTotal.asMoney()}).",
            onConfirm = { viewModel.deleteSpace(space) },
            onDismiss = { pendingDelete = null },
        )
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
        maxLines = 1,
    )
}

/**
 * Τα ποσά στοιχίζονται δεξιά με σταθερά βάρη στηλών και ενιαίο μέγεθος
 * γραμματοσειράς, ώστε οι τελείες των δεκαδικών να πέφτουν στην ίδια στήλη.
 */
@Composable
private fun SpaceRow(space: SpaceEntity, onEdit: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onEdit),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            space.description,
            Modifier.weight(2.0f),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
        )
        AmountCell(space.area.asNumber(), 1.0f)
        AmountCell(space.unitPrice.asMoney(), 1.2f)
        AmountCell(space.lineTotal.asMoney(), 1.3f, bold = true)
        Row(Modifier.width(ACTIONS_WIDTH), horizontalArrangement = Arrangement.End) {
            IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Edit, "Επεξεργασία", tint = EditBlue, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Delete, "Διαγραφή", tint = DeleteRed, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun RowScope.AmountCell(text: String, weight: Float, bold: Boolean = false) {
    Text(
        text,
        modifier = Modifier.weight(weight),
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
        textAlign = TextAlign.End,
        maxLines = 1,
        fontWeight = if (bold) FontWeight.Medium else FontWeight.Normal,
    )
}

@Composable
private fun SpaceEditor(
    space: SpaceEntity,
    onSave: (SpaceEntity) -> Unit,
    onCancel: () -> Unit,
) {
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
            StableTextField(
                value = description,
                onValueChange = { description = it },
                label = "Περιγραφή χώρου",
                debounceMillis = 0,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StableTextField(
                    value = area,
                    onValueChange = { area = it },
                    label = "Επιφάνεια",
                    debounceMillis = 0,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                StableTextField(
                    value = price,
                    onValueChange = { price = it },
                    label = "Τιμή μονάδος",
                    debounceMillis = 0,
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
            // Δύο κουμπιά σε πλήρες πλάτος: με τρία στριμώχνονταν και το
            // «Αποθήκευση» έσπαγε σε δύο γραμμές.
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
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Αποθήκευση") }

                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                    Text("Άκυρο", maxLines = 1)
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
        StableTextField(
            value = description,
            onValueChange = { description = it },
            label = "Περιγραφή χώρου",
            debounceMillis = 0,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StableTextField(
                value = area,
                onValueChange = { area = it },
                label = "Επιφάνεια",
                debounceMillis = 0,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            StableTextField(
                value = price,
                onValueChange = { price = it },
                label = "Τιμή μονάδος",
                debounceMillis = 0,
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
            Text("Προσθήκη χώρου", maxLines = 1)
        }
    }
}

// --------------------------------------------------- πρόσθετα κόστη -----

/**
 * Σκαλωσιά και άδεια μικρής κλίμακας εργασιών.
 *
 * Χρεώνονται μόνο σε κάποιες δουλειές και το ποσό αλλάζει κάθε φορά, γι' αυτό
 * διακόπτης και πεδίο μαζί. Το ποσό δεν σβήνεται όταν ο διακόπτης κλείσει: αν
 * ξανανοίξει, είναι εκεί.
 */
@Composable
private fun ExtrasCard(details: OfferWithDetails, viewModel: OffersViewModel) {
    val offer = details.offer

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Πρόσθετα κόστη", style = MaterialTheme.typography.titleMedium)

            ExtraCost(
                label = "Σκαλωσιά",
                enabled = offer.scaffolding,
                amount = offer.scaffoldingCost,
                onEnabled = { viewModel.updateOffer(offer.copy(scaffolding = it)) },
                onAmount = { viewModel.updateOffer(offer.copy(scaffoldingCost = it)) },
            )
            ExtraCost(
                label = "Άδεια μικρής κλίμακας εργασιών",
                enabled = offer.permit,
                amount = offer.permitCost,
                onEnabled = { viewModel.updateOffer(offer.copy(permit = it)) },
                onAmount = { viewModel.updateOffer(offer.copy(permitCost = it)) },
            )

            if (!offer.scaffolding && !offer.permit) {
                Text(
                    "Όσα δεν είναι επιλεγμένα δεν εμφανίζονται καθόλου στο PDF.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ExtraCost(
    label: String,
    enabled: Boolean,
    amount: Double,
    onEnabled: (Boolean) -> Unit,
    onAmount: (Double) -> Unit,
) {
    var amountText by remember(label, enabled) {
        mutableStateOf(if (amount > 0.0) amount.toString().removeSuffix(".0") else "")
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = enabled, onCheckedChange = onEnabled)
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
        }
        // Το πεδίο μπαίνει από κάτω και όχι δίπλα: με μεγάλη γραμματοσειρά ή
        // μακρύ τίτλο, δίπλα στο checkbox δεν του μένει πλάτος
        if (enabled) {
            StableTextField(
                value = amountText,
                onValueChange = { text ->
                        amountText = text
                        onAmount(text.parseDecimal() ?: 0.0)
                    },
                label = "Ποσό (€)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(start = 48.dp, bottom = 4.dp),
            )
        }
    }
}

// ------------------------------------------------------------------- ΦΠΑ -----

/**
 * Τα σύνολα που κλείνουν την προσφορά, και ο διακόπτης του ΦΠΑ.
 *
 * Ο συντελεστής δεν ρυθμίζεται: για ελαιοχρωματισμούς δεν υπάρχει μειωμένος,
 * οπότε ένα πεδίο «ποσοστό» θα ήταν μόνο ευκαιρία για λάθος. Η κάρτα δείχνει
 * ακριβώς τις γραμμές που θα τυπωθούν στο κάτω μέρος του πίνακα.
 */
@Composable
private fun VatCard(details: OfferWithDetails, viewModel: OffersViewModel) {
    val offer = details.offer
    // Η έτοιμη παρατήρηση λέει το αντίθετο· με αναμμένο ΦΠΑ πρέπει να φύγει
    val contradicting = details.notes.firstOrNull {
        it.text.contains("δεν περιλαμβάνεται ο ΦΠΑ", ignoreCase = true)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Σύνολα & ΦΠΑ", style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = offer.vatIncluded,
                    onCheckedChange = { viewModel.updateOffer(offer.copy(vatIncluded = it)) },
                )
                Text(
                    "Υπολογισμός ΦΠΑ 24%",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
            }

            HorizontalDivider()
            if (details.scaffoldingCost > 0.0 || details.permitCost > 0.0) {
                AmountLine("Χώροι", details.linesTotal.asMoney())
                if (details.scaffoldingCost > 0.0) {
                    AmountLine("Σκαλωσιά", details.scaffoldingCost.asMoney())
                }
                if (details.permitCost > 0.0) {
                    AmountLine("Άδεια μικρής κλίμακας", details.permitCost.asMoney())
                }
            }
            if (offer.vatIncluded) {
                AmountLine("Σύνολο", details.total.asMoney(), bold = true)
                AmountLine("ΦΠΑ 24%", details.vatAmount.asMoney())
            }
            HorizontalDivider()
            AmountLine("Γενικό σύνολο", details.grandTotal.asMoney(), bold = true)

            if (offer.vatIncluded && contradicting != null) {
                Text(
                    "Η παρατήρηση «${contradicting.text}» λέει το αντίθετο.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DeleteRed,
                )
                TextButton(onClick = { viewModel.deleteNote(contradicting) }) {
                    Text("Αφαίρεση παρατήρησης")
                }
            }
        }
    }
}

@Composable
private fun AmountLine(label: String, value: String, bold: Boolean = false) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = weight,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = weight,
            textAlign = TextAlign.End,
        )
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
    var pendingDelete by remember { mutableStateOf<gr.prosfora.app.data.db.NoteEntity?>(null) }

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
            details.notes.filter { it.text !in presets }.forEach { note ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = true, onCheckedChange = { pendingDelete = note })
                    Text(
                        note.text,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(start = 4.dp),
                    )
                    IconButton(onClick = { pendingDelete = note }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, "Διαγραφή", tint = DeleteRed, modifier = Modifier.size(18.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            FreeNoteInput { text, pin -> viewModel.addFreeNote(details.offer.id, text, pin) }
        }
    }

    pendingDelete?.let { note ->
        ConfirmDialog(
            title = "Διαγραφή σημείωσης",
            message = note.text,
            onConfirm = { viewModel.deleteNote(note) },
            onDismiss = { pendingDelete = null },
        )
    }
}

@Composable
private fun FreeNoteInput(onAdd: (String, Boolean) -> Unit) {
    var text by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StableTextField(
            value = text,
            onValueChange = { text = it },
            label = "Νέα σημείωση",
            singleLine = false,
            minLines = 2,
            debounceMillis = 0,
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
            Text("Προσθήκη σημείωσης", maxLines = 1)
        }
    }
}

// ------------------------------------------------- ισχύς & τρόπος πληρωμής ---

/**
 * Τα δύο στοιχεία που κλείνουν την προσφορά: ως πότε ισχύει και πώς πληρώνεται.
 * Και τα δύο τυπώνονται στο PDF, όπως ακριβώς στα φύλλα προσφοράς που έφτιαχνε
 * ο χρήστης με το χέρι.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TermsCard(details: OfferWithDetails, viewModel: OffersViewModel) {
    val context = LocalContext.current
    val offer = details.offer
    var picking by remember { mutableStateOf(false) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ισχύς & τρόπος πληρωμής", style = MaterialTheme.typography.titleMedium)

            // FlowRow και όχι Row: με μεγάλη γραμματοσειρά ή στενή οθόνη το
            // κουμπί δίπλα στο chip στριμωχνόταν και το κείμενο κοβόταν
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AssistChip(
                    onClick = { picking = true },
                    label = {
                        Text(
                            offer.validUntilDay?.let { "Ισχύει έως ${it.asOfferDate()}" }
                                ?: "Χωρίς ημερομηνία λήξης",
                        )
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.EventAvailable,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = if (details.expired()) DeleteRed else SentGreen,
                        )
                    },
                )
                if (offer.validUntilDay != null) {
                    // Εικονίδιο αντί για κείμενο: δεν έχει τι να κοπεί
                    IconButton(onClick = {
                        viewModel.updateOffer(offer.copy(validUntilDay = null))
                    }) {
                        Icon(
                            Icons.Default.Clear,
                            contentDescription = "Καθαρισμός ημερομηνίας",
                            tint = DeleteRed,
                        )
                    }
                }
            }
            if (details.expired()) {
                Text(
                    "Η προσφορά έχει λήξει — άλλαξε την ημερομηνία πριν τη στείλεις ξανά.",
                    style = MaterialTheme.typography.bodySmall,
                    color = DeleteRed,
                )
            }

            StableTextField(
                value = offer.paymentTerms,
                onValueChange = { viewModel.updateOffer(offer.copy(paymentTerms = it)) },
                label = "Τρόπος πληρωμής",
                placeholder = "Μία δόση ανά γραμμή",
                singleLine = false,
                minLines = 4,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Κάθε γραμμή τυπώνεται ως ξεχωριστή δόση στο PDF.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (offer.paymentTerms.isBlank()) {
                TextButton(
                    onClick = {
                        viewModel.updateOffer(
                            offer.copy(paymentTerms = GoogleSettings(context).defaultPaymentTerms),
                        )
                    },
                ) { Text("Συμπλήρωση από τις προεπιλογές") }
            }
        }
    }

    if (picking) {
        val initial = offer.validUntilDay ?: LocalDate.now().plusDays(60).toEpochDay()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        // Ο DatePicker δουλεύει σε UTC· η μετατροπή γίνεται εκεί,
                        // αλλιώς η τοπική ζώνη μετακινεί τη μέρα κατά μία
                        val day = java.time.Instant.ofEpochMilli(millis)
                            .atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                        viewModel.updateOffer(offer.copy(validUntilDay = day))
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
}

// -------------------------------------------------------------- ιστορικό -----

@Composable
private fun HistoryCard(details: OfferWithDetails) {
    val offer = details.offer
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Ιστορικό αποστολών", style = MaterialTheme.typography.titleMedium)
            offer.lastSentAt?.let {
                Text("✉ Email · ${it.asSentStamp()}", style = MaterialTheme.typography.bodySmall, color = SentGreen)
            }
            offer.notifiedAt?.let {
                val via = if (offer.notifiedVia.equals("VIBER", true)) "Viber" else "SMS"
                Text("✆ $via · ${it.asSentStamp()}", style = MaterialTheme.typography.bodySmall, color = SentGreen)
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
                        label = { Text(status.label, maxLines = 1) },
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
