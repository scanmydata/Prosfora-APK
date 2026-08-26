package gr.prosfora.app.ui.jobs

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.SendMethod
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.mail.GmailSender
import gr.prosfora.app.mail.MailSender
import gr.prosfora.app.message.MessageTemplates
import gr.prosfora.app.notify.Channel
import gr.prosfora.app.notify.ContactNotifier
import gr.prosfora.app.notify.SmsSender
import gr.prosfora.app.settings.SmtpSettingsStore
import gr.prosfora.app.ui.offers.OffersViewModel
import kotlinx.coroutines.launch

private enum class ReviewChannel(val label: String) { EMAIL("Email"), SMS("SMS"), VIBER("Viber") }

/**
 * Αίτημα αξιολόγησης μετά την ολοκλήρωση των εργασιών. Ίδια φιλοσοφία με τις
 * υπόλοιπες αποστολές: βλέπεις και αλλάζεις το κείμενο πριν φύγει.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewComposeScreen(
    viewModel: OffersViewModel,
    offerId: String,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { GoogleSettings(context) }
    val authorizer = rememberGoogleAuthorizer()

    val offers by viewModel.offers.collectAsState()
    val details = remember(offers, offerId) { offers.firstOrNull { it.offer.id == offerId } }
        ?: return

    var channel by remember {
        mutableStateOf(
            if (details.offer.email.isNotBlank()) ReviewChannel.EMAIL else ReviewChannel.SMS,
        )
    }
    var text by remember(offerId) {
        mutableStateOf(
            MessageTemplates.render(
                settings.reviewTemplate,
                details,
                settings.reviewLink,
                settings.greetingOptions,
            ),
        )
    }
    var recipient by remember(offerId, channel) {
        mutableStateOf(
            if (channel == ReviewChannel.EMAIL) details.offer.email else details.offer.customerPhone,
        )
    }
    var busy by remember { mutableStateOf(false) }
    var askViber by remember { mutableStateOf(false) }

    fun done() {
        viewModel.markReviewSent(details.offer)
        Toast.makeText(context, "Καταγράφηκε ως σταλμένο", Toast.LENGTH_SHORT).show()
        onBack()
    }

    val smsPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            busy = true
            scope.launch {
                val result = SmsSender.send(context, recipient, text)
                busy = false
                result.onSuccess { done() }.onFailure {
                    Toast.makeText(context, "Απέτυχε: ${it.message}", Toast.LENGTH_LONG).show()
                }
            }
        } else if (ContactNotifier.openSmsApp(context, recipient, text)) {
            askViber = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Αίτημα αξιολόγησης") },
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReviewChannel.entries.forEach { entry ->
                    val enabled = when (entry) {
                        ReviewChannel.EMAIL -> details.offer.email.isNotBlank()
                        else -> details.offer.customerPhone.isNotBlank()
                    }
                    FilterChip(
                        selected = channel == entry,
                        enabled = enabled,
                        onClick = { channel = entry },
                        label = { Text(entry.label, maxLines = 1) },
                        leadingIcon = {
                            Icon(
                                when (entry) {
                                    ReviewChannel.EMAIL -> Icons.Default.Email
                                    ReviewChannel.SMS -> Icons.Default.Sms
                                    ReviewChannel.VIBER -> Icons.Default.Send
                                },
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                            )
                        },
                    )
                }
            }

            if (channel != ReviewChannel.VIBER) {
                OutlinedTextField(
                    value = recipient,
                    onValueChange = { recipient = it },
                    label = { Text(if (channel == ReviewChannel.EMAIL) "Email" else "Κινητό") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Μήνυμα") },
                minLines = 8,
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
                }
            }

            Button(
                enabled = !busy && text.isNotBlank() &&
                    (channel == ReviewChannel.VIBER || recipient.isNotBlank()),
                onClick = {
                    when (channel) {
                        ReviewChannel.VIBER ->
                            if (ContactNotifier.openViber(context, text)) {
                                askViber = true
                            } else {
                                Toast.makeText(context, "Δεν βρέθηκε το Viber", Toast.LENGTH_LONG).show()
                            }

                        ReviewChannel.SMS -> {
                            val granted = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.SEND_SMS,
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) {
                                busy = true
                                scope.launch {
                                    val result = SmsSender.send(context, recipient, text)
                                    busy = false
                                    result.onSuccess { done() }.onFailure {
                                        Toast.makeText(context, "Απέτυχε: ${it.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            } else {
                                smsPermission.launch(Manifest.permission.SEND_SMS)
                            }
                        }

                        ReviewChannel.EMAIL -> {
                            busy = true
                            scope.launch {
                                val smtp = SmtpSettingsStore(context).load()
                                val message = MailSender.Outgoing(
                                    to = recipient.trim(),
                                    subject = MessageTemplates.REVIEW_SUBJECT,
                                    body = text,
                                    // Στο email ο σύνδεσμος γίνεται «Πατήστε εδώ»·
                                    // το σκέτο κείμενο μένει ως εναλλακτικό μέρος
                                    html = MessageTemplates.asHtml(text, settings.reviewLink),
                                )
                                val result = runCatching {
                                    if (settings.sendMethod == SendMethod.GOOGLE) {
                                        GmailSender.send(authorizer.accessToken(), message)
                                    } else {
                                        MailSender.send(smtp, message)
                                    }
                                }
                                busy = false
                                result.onSuccess { done() }.onFailure {
                                    Toast.makeText(context, "Απέτυχε: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("Αποστολή…", maxLines = 1)
                } else {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Αποστολή", maxLines = 1)
                }
            }
        }
    }

    if (askViber) {
        AlertDialog(
            onDismissRequest = { askViber = false },
            title = { Text("Στάλθηκε το μήνυμα;") },
            text = { Text("Δεν μπορώ να το επιβεβαιώσω αυτόματα.") },
            confirmButton = {
                TextButton(onClick = { askViber = false; done() }) { Text("Ναι, στάλθηκε") }
            },
            dismissButton = {
                TextButton(onClick = { askViber = false }) { Text("Όχι ακόμη") }
            },
        )
    }
}
