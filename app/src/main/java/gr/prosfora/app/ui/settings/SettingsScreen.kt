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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import gr.prosfora.app.BuildConfig
import gr.prosfora.app.data.SeedImporter
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.SendMethod
import gr.prosfora.app.mail.OfferMail
import gr.prosfora.app.mail.MailSender
import gr.prosfora.app.settings.SMTP_PRESETS
import gr.prosfora.app.settings.SmtpSettings
import gr.prosfora.app.settings.SmtpSettingsStore
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenTemplate: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SmtpSettingsStore(context) }

    var settings by remember { mutableStateOf(store.load()) }
    var testing by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var presetHint by remember { mutableStateOf<String?>(null) }

    val googleSettings = remember { GoogleSettings(context) }
    var sendMethod by remember { mutableStateOf(googleSettings.sendMethod) }
    var autoSync by remember { mutableStateOf(googleSettings.autoSync) }
    var lastSync by remember { mutableStateOf(googleSettings.lastSyncAt) }
    var syncing by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ρυθμίσεις") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Πίσω")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Τρόπος αποστολής", style = MaterialTheme.typography.titleMedium)
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
                }
            }

            if (sendMethod == SendMethod.SMTP) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Αποστολή email (SMTP)", style = MaterialTheme.typography.titleMedium)

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
                        settings = settings.copy(useStartTls = it, useSsl = if (it) false else settings.useSsl)
                    }
                    ToggleRow("SSL/TLS (θύρα 465)", settings.useSsl) {
                        settings = settings.copy(useSsl = it, useStartTls = if (it) false else settings.useStartTls)
                    }
                }
            }

            }

            SharedDatabaseCard(
                autoSync = autoSync,
                onAutoSyncChange = { autoSync = it; googleSettings.autoSync = it },
                lastSync = lastSync,
                syncing = syncing,
                onSyncingChange = { syncing = it },
                onSynced = { lastSync = googleSettings.lastSyncAt },
                googleSettings = googleSettings,
            )

            MessageTemplatesCard(googleSettings)

            ReviewSettingsCard(googleSettings)

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Αποστολέας", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = settings.fromAddress,
                        onValueChange = { settings = settings.copy(fromAddress = it) },
                        label = { Text("Διεύθυνση αποστολέα") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
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
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        store.save(settings)
                        googleSettings.senderName = settings.fromName
                        Toast.makeText(context, "Αποθηκεύτηκε", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Αποθήκευση") }

                OutlinedButton(
                    enabled = !testing && settings.isConfigured,
                    onClick = {
                        testing = true
                        scope.launch {
                            val result = runCatching { MailSender.verify(settings) }
                            testing = false
                            val text = result.fold(
                                onSuccess = { "Η σύνδεση πέτυχε" },
                                onFailure = { "Απέτυχε: ${it.message}" },
                            )
                            Toast.makeText(context, text, Toast.LENGTH_LONG).show()
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    if (testing) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Δοκιμή σύνδεσης")
                    }
                }
            }

            Text(
                "Ο κωδικός αποθηκεύεται κρυπτογραφημένος στη συσκευή και δεν φεύγει ποτέ από αυτήν.\n\n" +
                    "Έκδοση ${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
