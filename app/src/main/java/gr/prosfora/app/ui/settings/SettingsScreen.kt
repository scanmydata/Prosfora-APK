package gr.prosfora.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import gr.prosfora.app.BuildConfig
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.SendMethod
import gr.prosfora.app.settings.SMTP_PRESETS
import gr.prosfora.app.settings.SmtpSettingsStore
import gr.prosfora.app.ui.MenuButton
import gr.prosfora.app.ui.offers.EditBlue
import gr.prosfora.app.ui.offers.EmailAmber
import gr.prosfora.app.ui.offers.SentGreen

/**
 * Οι ρυθμίσεις σε ενότητες που ανοιγοκλείνουν: μία οθόνη, επτά θέματα, ανοίγει
 * μόνο αυτό που χρειάζεται τη στιγμή εκείνη.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onMenu: () -> Unit, onOpenTemplate: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SmtpSettingsStore(context) }
    val googleSettings = remember { GoogleSettings(context) }

    var settings by remember { mutableStateOf(store.load()) }
    var presetHint by remember { mutableStateOf<String?>(null) }
    var sendMethod by remember { mutableStateOf(googleSettings.sendMethod) }
    var autoSync by remember { mutableStateOf(googleSettings.autoSync) }
    var lastSync by remember { mutableStateOf(googleSettings.lastSyncAt) }
    var syncing by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ρυθμίσεις") },
                navigationIcon = { MenuButton(onMenu) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SettingsSection(
                title = "Αποστολή email",
                subtitle = when (sendMethod) {
                    SendMethod.GOOGLE -> "Μέσω του λογαριασμού Google"
                    SendMethod.SMTP -> settings.host.ifBlank { "SMTP — δεν έχει ρυθμιστεί" }
                },
                icon = Icons.Default.Email,
                tint = EmailAmber,
            ) {
                SendMethod.entries.forEach { method ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = sendMethod == method,
                            onClick = {
                                sendMethod = method
                                googleSettings.sendMethod = method
                            },
                        )
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(method.label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                method.hint,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                if (sendMethod == SendMethod.SMTP) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SMTP_PRESETS.forEach { preset ->
                            AssistChip(
                                onClick = {
                                    settings = settings.copy(
                                        host = preset.host,
                                        port = preset.port,
                                        useStartTls = preset.startTls,
                                        useSsl = preset.ssl,
                                    )
                                    presetHint = preset.hint
                                },
                                label = { Text(preset.label) },
                            )
                        }
                    }
                    presetHint?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    OutlinedTextField(
                        value = settings.host,
                        onValueChange = { settings = settings.copy(host = it) },
                        label = { Text("Διακομιστής SMTP") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = settings.port.toString(),
                        onValueChange = {
                            settings = settings.copy(port = it.toIntOrNull() ?: settings.port)
                        },
                        label = { Text("Θύρα") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = settings.username,
                        onValueChange = { settings = settings.copy(username = it) },
                        label = { Text("Όνομα χρήστη") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = settings.password,
                        onValueChange = { settings = settings.copy(password = it) },
                        label = { Text("Κωδικός / App password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    ToggleRow("STARTTLS", settings.useStartTls) {
                        settings = settings.copy(
                            useStartTls = it,
                            useSsl = if (it) false else settings.useSsl,
                        )
                    }
                    ToggleRow("SSL/TLS (θύρα 465)", settings.useSsl) {
                        settings = settings.copy(
                            useSsl = it,
                            useStartTls = if (it) false else settings.useStartTls,
                        )
                    }

                    // Η διεύθυνση αποστολέα αφορά ΜΟΝΟ το SMTP: εκεί την ορίζουμε
                    // εμείς στο μήνυμα. Με τον λογαριασμό Google τη βάζει η Gmail.
                    OutlinedTextField(
                        value = settings.fromAddress,
                        onValueChange = { settings = settings.copy(fromAddress = it) },
                        label = { Text("Διεύθυνση αποστολέα") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Η διεύθυνση που θα δει ο πελάτης ως «Από» και στην οποία θα " +
                            "απαντήσει. Συνήθως είναι ίδια με το όνομα χρήστη.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Ο αποστολέας είναι ο συνδεδεμένος λογαριασμός Google — τη " +
                            "διεύθυνση τη συμπληρώνει η ίδια η Gmail και τα μηνύματα " +
                            "μπαίνουν κανονικά στα Απεσταλμένα σου.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedTextField(
                    value = settings.fromName,
                    onValueChange = { settings = settings.copy(fromName = it) },
                    label = { Text("Όνομα αποστολέα") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = settings.signature,
                    onValueChange = { settings = settings.copy(signature = it) },
                    label = { Text("Υπογραφή") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = {
                        store.save(settings)
                        googleSettings.senderName = settings.fromName
                        Toast.makeText(context, "Αποθηκεύτηκε", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Αποθήκευση") }

                Text(
                    "Ο κωδικός αποθηκεύεται κρυπτογραφημένος στη συσκευή και δεν φεύγει " +
                        "ποτέ από αυτήν.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SettingsSection(
                title = "Κοινόχρηστη βάση",
                subtitle = if (googleSettings.spreadsheetId == null) {
                    "Δεν έχει συνδεθεί Sheet"
                } else {
                    "Google Sheet στον φάκελο ${GoogleSettings.DRIVE_FOLDER_NAME}"
                },
                icon = Icons.Default.CloudSync,
                tint = EditBlue,
            ) {
                SharedDatabaseSettings(
                    autoSync = autoSync,
                    onAutoSyncChange = { autoSync = it; googleSettings.autoSync = it },
                    lastSync = lastSync,
                    syncing = syncing,
                    onSyncingChange = { syncing = it },
                    onSynced = { lastSync = googleSettings.lastSyncAt },
                    googleSettings = googleSettings,
                )
            }

            SettingsSection(
                title = "Πρότυπο PDF",
                subtitle = "Η εμφάνιση της τυπωμένης προσφοράς",
                icon = Icons.Default.Description,
            ) {
                Text(
                    "Το πρότυπο είναι ένα Google Doc στον φάκελο " +
                        "«${GoogleSettings.DRIVE_FOLDER_NAME}». Μπορείς να αλλάξεις τα " +
                        "κείμενά του μέσα από την εφαρμογή ή ολόκληρη τη διάταξη στο " +
                        "Google Docs.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onOpenTemplate, modifier = Modifier.fillMaxWidth()) {
                    Text("Άνοιγμα προτύπου", maxLines = 1)
                }
            }

            SettingsSection(
                title = "Προεπιλογές προσφοράς",
                subtitle = "Ισχύς και τρόπος πληρωμής για κάθε νέα προσφορά",
                icon = Icons.Default.Payments,
                tint = SentGreen,
            ) {
                OfferDefaultsSettings(googleSettings)
            }

            SettingsSection(
                title = "Πρότυπα μηνυμάτων",
                subtitle = "Email, SMS και Viber",
                icon = Icons.AutoMirrored.Filled.Chat,
            ) {
                MessageTemplatesSettings(googleSettings)
            }

            SettingsSection(
                title = "Αξιολογήσεις",
                subtitle = "Μετά από ${googleSettings.reviewDelayDays} μέρες από την ολοκλήρωση",
                icon = Icons.Default.Star,
                tint = EmailAmber,
            ) {
                ReviewSettings(googleSettings)
            }

            SettingsSection(
                title = "Οφειλές",
                subtitle = "Ανάγνωση παραστατικών και ειδοποιήσεις",
                icon = Icons.Default.AccountBalance,
            ) {
                DebtSettings(googleSettings)
            }

            SettingsSection(
                title = "Δεδομένα",
                subtitle = "Εισαγωγή παλιών προσφορών από Excel",
                icon = Icons.Default.Inventory2,
            ) {
                HistoryImportSettings()
            }

            SettingsSection(
                title = "Σχετικά",
                subtitle = "Έκδοση ${BuildConfig.VERSION_NAME}",
                icon = Icons.Default.Info,
            ) {
                Text(
                    "Προσφορές — ΤοΒάψιμο.gr\n" +
                        "Έκδοση ${BuildConfig.VERSION_NAME}\n\n" +
                        "Οι ενημερώσεις έρχονται από το GitHub. Ο έλεγχος γίνεται με το " +
                        "κουμπί ανανέωσης ή τραβώντας τη λίστα προσφορών προς τα κάτω.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.padding(bottom = 8.dp))
        }
    }
}

/** Ισχύς και δόσεις που κληρονομεί κάθε νέα προσφορά. */
@Composable
private fun OfferDefaultsSettings(googleSettings: GoogleSettings) {
    val context = LocalContext.current
    var days by remember { mutableStateOf(googleSettings.offerValidDays.toString()) }
    var terms by remember { mutableStateOf(googleSettings.defaultPaymentTerms) }

    Text(
        "Αυτά μπαίνουν αυτόματα σε κάθε νέα προσφορά και αλλάζουν ελεύθερα ανά " +
            "προσφορά από την οθόνη της.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = days,
        onValueChange = { days = it.filter(Char::isDigit).take(4) },
        label = { Text("Ισχύς προσφοράς σε μέρες") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = terms,
        onValueChange = { terms = it },
        label = { Text("Τρόπος πληρωμής") },
        minLines = 4,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Μία δόση ανά γραμμή — έτσι ακριβώς τυπώνονται στο PDF.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Button(
        onClick = {
            googleSettings.offerValidDays = days.toIntOrNull() ?: 60
            googleSettings.defaultPaymentTerms = terms
            Toast.makeText(context, "Αποθηκεύτηκε", Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Αποθήκευση") }

    TextButton(
        onClick = { terms = GoogleSettings.DEFAULT_PAYMENT_TERMS },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Επαναφορά προεπιλογής") }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/**
 * Ρυθμίσεις των οφειλών.
 *
 * Το κλειδί του ocr.space ζει εδώ και όχι στον κώδικα για δύο λόγους: αλλάζει
 * χωρίς νέα έκδοση, και αδειάζοντάς το ο χρήστης σταματάει να στέλνει τα
 * παραστατικά του σε τρίτο πάροχο — μένει μόνο το OCR του Drive.
 */
@Composable
private fun DebtSettings(settings: GoogleSettings) {
    var askDate by remember { mutableStateOf(settings.askPaidDate) }
    var notify by remember { mutableStateOf(settings.notifyDriveChanges) }
    var key by remember { mutableStateOf(settings.ocrApiKey) }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Ημερομηνία εξόφλησης", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Τσεκάροντας μια οφειλή, ρωτάει πότε πληρώθηκε",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = askDate,
            onCheckedChange = { askDate = it; settings.askPaidDate = it },
        )
    }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Ειδοποιήσεις για το Drive", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Όταν κάποιος άλλος προσθέσει ή σβήσει αρχείο στον κοινόχρηστο φάκελο",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = notify,
            onCheckedChange = { notify = it; settings.notifyDriveChanges = it },
        )
    }

    OutlinedTextField(
        value = key,
        onValueChange = { key = it; settings.ocrApiKey = it },
        label = { Text("Κλειδί ocr.space") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Χρησιμοποιείται μόνο για παραστατικά χωρίς κείμενο μέσα τους — " +
            "σαρωμένα έντυπα και στιγμιότυπα οθόνης. Το αρχείο φεύγει στον " +
            "πάροχο· αν το πεδίο μείνει κενό, γίνεται OCR από το Google Drive.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
