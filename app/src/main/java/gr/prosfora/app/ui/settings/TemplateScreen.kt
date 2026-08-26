package gr.prosfora.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import gr.prosfora.app.doc.DocxTemplate
import gr.prosfora.app.doc.OfferPdf
import gr.prosfora.app.google.BuiltInTemplate
import gr.prosfora.app.google.DriveClient
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.SendMethod
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.mail.GmailSender
import gr.prosfora.app.mail.MailSender
import gr.prosfora.app.settings.SmtpSettingsStore
import gr.prosfora.app.ui.components.ConfirmDialog
import gr.prosfora.app.ui.pdf.PdfPreviewDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Το πρότυπο του PDF: ποιο ισχύει, επεξεργασία και προεπισκόπηση της τυπωμένης
 * σελίδας A4 με δείγμα δεδομένων.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateScreen(onBack: () -> Unit, onEditText: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()
    val settings = remember { GoogleSettings(context) }

    var busy by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<File?>(null) }
    var templateId by remember { mutableStateOf(settings.templateFileId) }
    var choice by remember { mutableStateOf(settings.builtInTemplate) }
    var confirmInstall by remember { mutableStateOf<BuiltInTemplate?>(null) }
    var askEmail by remember { mutableStateOf(false) }

    fun fail(error: Throwable) {
        Toast.makeText(context, "Απέτυχε: ${error.message}", Toast.LENGTH_LONG).show()
    }

    // Το SAF δεν φιλτράρει αξιόπιστα τα .docx: ο πάροχος του Drive δηλώνει άλλο
    // τύπο για τα Έγγραφα Google, οπότε με φίλτρο τύπου εμφανίζονται γκρίζα.
    // Δεχόμαστε τα πάντα και ελέγχουμε εμείς ότι είναι έγγραφο του Word.
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Ανέβασμα προτύπου…"
        scope.launch {
            val result = runCatching {
                val bytes = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: error("Δεν διαβάστηκε το αρχείο")
                }
                val drive = DriveClient(authorizer.accessToken())
                OfferPdf.installFromBytes(drive, settings, bytes)
            }
            busy = null
            result.onSuccess {
                templateId = it
                Toast.makeText(
                    context,
                    "Το πρότυπο αντιγράφηκε στο Drive, ρυθμισμένο για A4",
                    Toast.LENGTH_LONG,
                ).show()
            }.onFailure(::fail)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Πρότυπο προσφοράς") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Πίσω")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Πώς δουλεύει", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Το πρότυπο είναι ένα Google Doc στον φάκελο " +
                            "«${GoogleSettings.DRIVE_FOLDER_NAME}» του Drive σου. Ό,τι " +
                            "αλλάξεις εκεί εμφανίζεται στην επόμενη προσφορά.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    Text(
                        "Πεδία που συμπληρώνονται αυτόματα:",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Text(
                        PLACEHOLDER_HELP,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Έτοιμα πρότυπα", style = MaterialTheme.typography.titleMedium)
                    BuiltInTemplate.entries.forEach { option ->
                        TemplateOption(
                            option = option,
                            selected = choice == option,
                            onSelect = { choice = option },
                        )
                    }
                    Button(
                        enabled = busy == null,
                        onClick = { confirmInstall = choice },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Εγκατάσταση στο Drive") }
                }
            }

            Button(
                enabled = busy == null,
                onClick = {
                    scope.launch {
                        busy = "Άνοιγμα…"
                        runCatching {
                            val drive = DriveClient(authorizer.accessToken())
                            OfferPdf.ensureTemplate(context, drive, settings)
                        }.onSuccess { id ->
                            templateId = id
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(OfferPdf.templateEditUrl(id)))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure(::fail)
                        busy = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Επεξεργασία στο Google Docs")
            }

            Button(
                enabled = busy == null,
                onClick = onEditText,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Επεξεργασία κειμένων εδώ") }

            OutlinedButton(
                enabled = busy == null,
                onClick = {
                    if (recipientFor(context, settings).isBlank()) askEmail = true
                    else sendTemplate(context, scope, settings, authorizer, ::fail) { busy = it }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Αποστολή στο email μου") }

            OutlinedButton(
                enabled = busy == null,
                // Χωρίς φίλτρο τύπου: αλλιώς τα αρχεία του Drive βγαίνουν γκρίζα
                onClick = { picker.launch(arrayOf("*/*")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Αντικατάσταση από αρχείο Word") }

            Text(
                "Το αρχείο σου δεν αλλάζει: η εφαρμογή παίρνει αντίγραφο, το ρυθμίζει " +
                    "για σελίδα A4 με αρίθμηση σελίδων και το ανεβάζει στον φάκελο.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedButton(
                enabled = busy == null,
                onClick = {
                    scope.launch {
                        busy = "Δημιουργία προεπισκόπησης…"
                        runCatching {
                            val drive = DriveClient(authorizer.accessToken())
                            renderSamplePdf(context, drive, settings)
                        }.onSuccess { preview = it }.onFailure(::fail)
                        busy = null
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy != null) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.size(8.dp))
                    Text(busy!!)
                } else {
                    Text("Προεπισκόπηση σελίδας A4")
                }
            }

            templateId?.let {
                Text(
                    "Αναγνωριστικό προτύπου: $it",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    preview?.let { file -> PdfPreviewDialog(file) { preview = null } }

    confirmInstall?.let { option ->
        ConfirmDialog(
            title = "Εγκατάσταση «${option.label}»",
            message = "Το πρότυπο στο Drive θα αντικατασταθεί. Ό,τι έχεις αλλάξει σε " +
                "αυτό — κείμενα, εικόνες, διάταξη — θα χαθεί.",
            confirmLabel = "Εγκατάσταση",
            confirmColor = MaterialTheme.colorScheme.primary,
            onConfirm = {
                busy = "Εγκατάσταση…"
                scope.launch {
                    val result = runCatching {
                        val drive = DriveClient(authorizer.accessToken())
                        OfferPdf.installBuiltIn(context, drive, settings, option)
                    }
                    busy = null
                    result.onSuccess {
                        templateId = it
                        preview = null
                        Toast.makeText(
                            context,
                            "Ενεργό πρότυπο: ${option.label}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }.onFailure(::fail)
                }
            },
            onDismiss = { confirmInstall = null },
        )
    }

    if (askEmail) {
        var draft by remember { mutableStateOf(settings.ownerEmail) }
        AlertDialog(
            onDismissRequest = { askEmail = false },
            title = { Text("Η διεύθυνσή σου") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Δεν ξέρω σε ποια διεύθυνση να στείλω. Συνήθως τη μαθαίνω από " +
                            "τον λογαριασμό Google — αν δεν έχει δοθεί ακόμη έγκριση, " +
                            "συμπλήρωσέ τη εδώ.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Email") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = draft.contains("@"),
                    onClick = {
                        settings.ownerEmail = draft
                        askEmail = false
                        sendTemplate(context, scope, settings, authorizer, ::fail) { busy = it }
                    },
                ) { Text("Αποστολή") }
            },
            dismissButton = {
                TextButton(onClick = { askEmail = false }) { Text("Άκυρο") }
            },
        )
    }
}

@Composable
private fun TemplateOption(
    option: BuiltInTemplate,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(12.dp),
            )
            .selectable(selected = selected, onClick = onSelect)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(Modifier.padding(start = 8.dp)) {
            Text(option.label, style = MaterialTheme.typography.bodyLarge)
            Text(
                option.hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Πού στέλνει το «στο email μου»: πρώτα ο λογαριασμός Google, μετά το SMTP. */
private fun recipientFor(
    context: android.content.Context,
    settings: GoogleSettings,
): String {
    val fromGoogle = settings.ownerEmail
    if (fromGoogle.contains("@")) return fromGoogle
    val smtp = SmtpSettingsStore(context).load().fromAddress
    return if (smtp.contains("@")) smtp else ""
}

private fun sendTemplate(
    context: android.content.Context,
    scope: kotlinx.coroutines.CoroutineScope,
    settings: GoogleSettings,
    authorizer: gr.prosfora.app.google.GoogleAuthorizer,
    onError: (Throwable) -> Unit,
    onBusy: (String?) -> Unit,
) {
    onBusy("Αποστολή προτύπου…")
    scope.launch {
        val result = runCatching {
            val drive = DriveClient(authorizer.accessToken())
            val docx = OfferPdf.fetchTemplateDocx(context, drive, settings)
            val file = File(context.cacheDir, "${GoogleSettings.TEMPLATE_NAME}.docx")
            file.writeBytes(docx)

            val to = recipientFor(context, settings)
            require(to.contains("@")) { "Δεν έχει οριστεί η διεύθυνσή σου" }

            val message = MailSender.Outgoing(
                to = to,
                subject = "Πρότυπο προσφοράς για επεξεργασία",
                body = """
                    Επισυνάπτεται το τρέχον πρότυπο.

                    Άλλαξέ το και στείλ' το πίσω στον εαυτό σου· μετά, από την οθόνη
                    «Πρότυπο προσφοράς» πάτα «Αντικατάσταση από αρχείο Word».
                """.trimIndent(),
                attachment = file,
                attachmentName = file.name,
            )
            if (settings.sendMethod == SendMethod.GOOGLE) {
                GmailSender.send(authorizer.accessToken(), message)
            } else {
                MailSender.send(SmtpSettingsStore(context).load(), message)
            }
            to
        }
        onBusy(null)
        result.onSuccess {
            Toast.makeText(context, "Στάλθηκε στο $it", Toast.LENGTH_LONG).show()
        }.onFailure(onError)
    }
}

/** Παράγει PDF από το τρέχον πρότυπο με ενδεικτικά δεδομένα. */
private suspend fun renderSamplePdf(
    context: android.content.Context,
    drive: DriveClient,
    settings: GoogleSettings,
): File = withContext(Dispatchers.IO) {
    val templateDocx = OfferPdf.fetchTemplateDocx(context, drive, settings)
    val rendered = DocxTemplate.render(templateDocx, SampleOffer.value)

    val folderId = settings.folderId
        ?: drive.findOrCreateFolder(GoogleSettings.DRIVE_FOLDER_NAME).also { settings.folderId = it }

    val tempId = drive.upload(
        name = "Προεπισκόπηση προτύπου (προσωρινό)",
        bytes = rendered,
        mimeType = DriveClient.DOCX_MIME,
        parentId = folderId,
        convertToGoogleDoc = true,
    )
    try {
        val pdf = drive.export(tempId, DriveClient.PDF_MIME)
        val target = File(File(context.cacheDir, "preview"), "template.pdf")
        target.parentFile?.mkdirs()
        target.writeBytes(pdf)
        target
    } finally {
        runCatching { drive.delete(tempId) }
    }
}

private val PLACEHOLDER_HELP = """
    Κεφαλίδα: <<[Οδός / Περιοχή]>> · <<[Είδος]>> · <<[Ημερομηνία]>> · <<[Ισχύει έως]>>
    Σύνολο: <<[Γενικό Σύνολο Live]>>
    Γραμμή χώρου: <<[Περιγραφή Χώρου]>> · <<[Επιφάνεια (τ.μ.)]>> · <<[Τιμή Μονάδος]>> · <<[Σύνολο Γραμμής]>>
    Μία γραμμή ανά σημείωση: <<[Παρατηρήσεις]>>
    Μία γραμμή ανά δόση: <<[Τρόπος Πληρωμής]>>
""".trimIndent()
