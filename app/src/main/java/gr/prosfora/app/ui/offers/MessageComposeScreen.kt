package gr.prosfora.app.ui.offers

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.message.MessageTemplates
import gr.prosfora.app.notify.Channel
import gr.prosfora.app.notify.ContactNotifier
import gr.prosfora.app.notify.SmsSender
import gr.prosfora.app.util.reason
import kotlinx.coroutines.launch

/**
 * Προεπισκόπηση και επεξεργασία του μηνύματος πριν σταλεί — η ίδια λογική με το
 * email, ώστε να μη φεύγει ποτέ κάτι που δεν είδε ο χρήστης.
 *
 * SMS: φεύγει από το ίδιο το app και επιβεβαιώνεται από τον πάροχο.
 * Viber: ανοίγει η εφαρμογή και ο χρήστης επιβεβαιώνει ο ίδιος, γιατί το Viber
 * δεν επιστρέφει αποτέλεσμα.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageComposeScreen(
    viewModel: OffersViewModel,
    channel: Channel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { GoogleSettings(context) }

    val details by viewModel.selectedOffer.collectAsState()
    val current = details ?: return

    var phone by remember(current.offer.id) { mutableStateOf(current.offer.customerPhone) }
    var text by remember(current.offer.id, channel) {
        mutableStateOf(
            MessageTemplates.render(
                when (channel) {
                    Channel.SMS -> settings.smsTemplate
                    Channel.VIBER -> settings.viberTemplate
                },
                current,
                greeting = settings.greetingOptions,
            ),
        )
    }
    var busy by remember { mutableStateOf(false) }
    var awaitingViberAnswer by remember { mutableStateOf(false) }

    fun recordSent() {
        viewModel.markNotified(current.offer.id, channel.storedValue)
        Toast.makeText(context, "Καταγράφηκε ως σταλμένο", Toast.LENGTH_SHORT).show()
        onBack()
    }

    val smsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (!granted) {
            // Χωρίς το δικαίωμα δεν μπορούμε να επιβεβαιώσουμε την αποστολή·
            // πέφτουμε πίσω στην εφαρμογή μηνυμάτων και ρωτάμε τον χρήστη.
            if (ContactNotifier.openSmsApp(context, phone, text)) {
                awaitingViberAnswer = true
            } else {
                Toast.makeText(context, "Δεν βρέθηκε εφαρμογή μηνυμάτων", Toast.LENGTH_LONG).show()
            }
        } else {
            busy = true
            scope.launch {
                val result = SmsSender.send(context, phone, text)
                busy = false
                result.onSuccess { recordSent() }.onFailure {
                    Toast.makeText(context, "Απέτυχε: ${it.reason()}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(channel.label) },
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
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("Κινητό παραλήπτη") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Μήνυμα") },
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Προεπισκόπηση", style = MaterialTheme.typography.labelLarge)
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                    if (channel == Channel.SMS) {
                        val parts = (text.length + 152) / 153
                        Text(
                            "${text.length} χαρακτήρες · $parts ${if (parts == 1) "μήνυμα" else "μηνύματα"}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Text(
                when (channel) {
                    Channel.SMS ->
                        "Το SMS φεύγει από την εφαρμογή και επιβεβαιώνεται από τον πάροχο."
                    Channel.VIBER ->
                        "Θα ανοίξει το Viber με το κείμενο έτοιμο. Το Viber δεν μας " +
                            "ενημερώνει αν πάτησες αποστολή, οπότε θα σε ρωτήσω μετά."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                enabled = !busy && phone.isNotBlank() && text.isNotBlank(),
                onClick = {
                    when (channel) {
                        Channel.SMS -> {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.SEND_SMS,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                busy = true
                                scope.launch {
                                    val result = SmsSender.send(context, phone, text)
                                    busy = false
                                    result.onSuccess { recordSent() }.onFailure {
                                        Toast.makeText(
                                            context,
                                            "Απέτυχε: ${it.reason()}",
                                            Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            } else {
                                smsPermission.launch(Manifest.permission.SEND_SMS)
                            }
                        }
                        Channel.VIBER -> {
                            if (ContactNotifier.openViber(context, text)) {
                                awaitingViberAnswer = true
                            } else {
                                Toast.makeText(
                                    context,
                                    "Δεν βρέθηκε το Viber στη συσκευή",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Αποστολή…")
                } else {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Αποστολή")
                }
            }
        }
    }

    if (awaitingViberAnswer) {
        AlertDialog(
            onDismissRequest = { awaitingViberAnswer = false },
            title = { Text("Στάλθηκε το μήνυμα;") },
            text = {
                Text(
                    "Δεν μπορώ να το επιβεβαιώσω αυτόματα. Αν το έστειλες, θα το " +
                        "καταγράψω στην προσφορά.",
                )
            },
            confirmButton = {
                TextButton(onClick = { awaitingViberAnswer = false; recordSent() }) {
                    Text("Ναι, στάλθηκε")
                }
            },
            dismissButton = {
                TextButton(onClick = { awaitingViberAnswer = false }) { Text("Όχι ακόμη") }
            },
        )
    }
}
