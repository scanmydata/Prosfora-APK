package gr.prosfora.app.ui.offers

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import gr.prosfora.app.doc.OfferPdf
import gr.prosfora.app.google.DriveClient
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.mail.MailSender
import gr.prosfora.app.mail.OfferMail
import gr.prosfora.app.settings.SmtpSettingsStore
import gr.prosfora.app.ui.pdf.PdfPreview
import kotlinx.coroutines.launch
import java.io.File

/**
 * Προεπισκόπηση και επεξεργασία του email πριν σταλεί: παραλήπτης, θέμα, σώμα
 * και το συνημμένο PDF — όλα ορατά και αλλάξιμα.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmailComposeScreen(
    viewModel: OffersViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()

    val details by viewModel.selectedOffer.collectAsState()
    val current = details ?: return

    val smtpStore = remember { SmtpSettingsStore(context) }
    val googleSettings = remember { GoogleSettings(context) }
    val smtp = remember { smtpStore.load() }

    var to by remember(current.offer.id) { mutableStateOf(current.offer.email) }
    var subject by remember(current.offer.id) {
        mutableStateOf(OfferMail.subject(googleSettings.emailSubjectTemplate, current))
    }
    var body by remember(current.offer.id) {
        mutableStateOf(OfferMail.body(googleSettings.emailBodyTemplate, current, smtp))
    }

    var pdf by remember(current.offer.id) {
        mutableStateOf(OfferPdf.pdfFile(context, current).takeIf { it.exists() })
    }
    var busy by remember { mutableStateOf<String?>(null) }
    var showPreview by remember { mutableStateOf(false) }

    // Το PDF παράγεται αυτόματα την πρώτη φορά ώστε να είναι έτοιμο ως συνημμένο
    LaunchedEffect(current.offer.id) {
        if (pdf == null) {
            busy = "Δημιουργία PDF…"
            runCatching {
                val drive = DriveClient(authorizer.accessToken())
                OfferPdf.generate(context, drive, googleSettings, current)
            }.onSuccess { pdf = it }
                .onFailure {
                    Toast.makeText(context, "Το PDF δεν δημιουργήθηκε: ${it.message}", Toast.LENGTH_LONG).show()
                }
            busy = null
        }
    }

    if (showPreview && pdf != null) {
        PdfPreviewDialog(pdf!!) { showPreview = false }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Αποστολή προσφοράς") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            OutlinedTextField(
                value = to,
                onValueChange = { to = it },
                label = { Text("Προς") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = subject,
                onValueChange = { subject = it },
                label = { Text("Θέμα") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = body,
                onValueChange = { body = it },
                label = { Text("Μήνυμα") },
                minLines = 8,
                modifier = Modifier.fillMaxWidth(),
            )

            AttachmentCard(
                pdf = pdf,
                busy = busy,
                onPreview = { showPreview = true },
                onRegenerate = {
                    scope.launch {
                        busy = "Ανανέωση PDF…"
                        runCatching {
                            val drive = DriveClient(authorizer.accessToken())
                            OfferPdf.generate(context, drive, googleSettings, current)
                        }.onSuccess { pdf = it }
                            .onFailure {
                                Toast.makeText(context, "Απέτυχε: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        busy = null
                    }
                },
            )

            if (!smtp.isConfigured) {
                Text(
                    "⚠️ Δεν έχουν ρυθμιστεί τα στοιχεία αποστολής — άνοιξε τις Ρυθμίσεις.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                enabled = busy == null && smtp.isConfigured && to.isNotBlank(),
                onClick = {
                    scope.launch {
                        busy = "Αποστολή…"
                        val result = runCatching {
                            MailSender.send(
                                smtp,
                                OfferMail.compose(to.trim(), subject, body, current, pdf),
                            )
                        }
                        busy = null
                        result.onSuccess {
                            Toast.makeText(context, "Το email στάλθηκε", Toast.LENGTH_SHORT).show()
                            viewModel.markSent(current.offer.id)
                            onBack()
                        }.onFailure {
                            Toast.makeText(context, "Αποτυχία: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy != null) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(busy!!)
                } else {
                    Icon(Icons.Default.Send, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Αποστολή")
                }
            }
        }
    }
}

@Composable
private fun AttachmentCard(
    pdf: File?,
    busy: String?,
    onPreview: () -> Unit,
    onRegenerate: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.PictureAsPdf, contentDescription = null)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Συνημμένο", style = MaterialTheme.typography.labelMedium)
                Text(
                    when {
                        busy != null -> busy
                        pdf != null -> "${pdf.length() / 1024} KB"
                        else -> "Δεν δημιουργήθηκε"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (pdf != null && busy == null) {
                TextButton(onClick = onPreview) { Text("Προεπισκόπηση") }
            }
            TextButton(onClick = onRegenerate, enabled = busy == null) { Text("Ανανέωση") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfPreviewDialog(file: File, onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Προεπισκόπηση PDF") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Κλείσιμο")
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                PdfPreview(file)
            }
        }
    }
}
