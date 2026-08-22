package gr.prosfora.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.doc.DocxTemplate
import gr.prosfora.app.doc.OfferPdf
import gr.prosfora.app.google.DriveClient
import gr.prosfora.app.google.DriveWorkspace
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.SendMethod
import gr.prosfora.app.mail.GmailSender
import gr.prosfora.app.mail.MailSender
import gr.prosfora.app.settings.SmtpSettingsStore
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.ui.pdf.PdfPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Το πρότυπο του PDF: επεξεργασία στο Google Docs και προεπισκόπηση της
 * τυπωμένης σελίδας A4, με δείγμα δεδομένων ώστε να φαίνεται πώς θα βγει.
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

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Ανέβασμα προτύπου…"
        scope.launch {
            val result = runCatching {
                val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("Δεν διαβάστηκε το αρχείο")
                val drive = DriveClient(authorizer.accessToken())
                // Το παλιό πρότυπο διαγράφεται ώστε να μη μείνουν δύο με το ίδιο όνομα
                settings.templateFileId?.let { old -> runCatching { drive.delete(old) } }
                settings.templateFileId = null
                val folder = DriveWorkspace(drive, settings).rootFolder()
                drive.upload(
                    name = GoogleSettings.TEMPLATE_NAME,
                    bytes = bytes,
                    mimeType = DriveClient.DOCX_MIME,
                    parentId = folder,
                    convertToGoogleDoc = true,
                ).also { settings.templateFileId = it }
            }
            busy = null
            result.onSuccess {
                Toast.makeText(context, "Το πρότυπο αντικαταστάθηκε", Toast.LENGTH_LONG).show()
            }.onFailure {
                Toast.makeText(context, "Απέτυχε: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            androidx.compose.material3.TopAppBar(
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
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Πώς δουλεύει", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Το πρότυπο είναι ένα κανονικό Google Doc στον φάκελο «Προσφορές» " +
                            "του Drive σου. Ό,τι αλλάξεις εκεί — λογότυπο, κείμενα, δείγματα " +
                            "εργασιών — εμφανίζεται στην επόμενη προσφορά.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider()
                    Text("Πεδία που συμπληρώνονται αυτόματα:", style = MaterialTheme.typography.labelMedium)
                    Text(
                        PLACEHOLDER_HELP,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                        }.onFailure {
                            Toast.makeText(context, "Απέτυχε: ${it.message}", Toast.LENGTH_LONG).show()
                        }
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
            ) { Text("Επεξεργασία κειμένων εδώ", maxLines = 1) }

            OutlinedButton(
                enabled = busy == null,
                onClick = {
                    busy = "Αποστολή προτύπου…"
                    scope.launch {
                        val result = runCatching {
                            val drive = DriveClient(authorizer.accessToken())
                            val docx = OfferPdf.fetchTemplateDocx(context, drive, settings)
                            val file = File(context.cacheDir, "${GoogleSettings.TEMPLATE_NAME}.docx")
                            file.writeBytes(docx)
                            val smtp = SmtpSettingsStore(context).load()
                            val to = smtp.fromAddress.ifBlank { settings.senderName }
                            require(to.contains("@")) {
                                "Δεν βρέθηκε δική σου διεύθυνση — συμπλήρωσε «Διεύθυνση αποστολέα» στις Ρυθμίσεις"
                            }
                            val message = MailSender.Outgoing(
                                to = to,
                                subject = "Πρότυπο προσφοράς για επεξεργασία",
                                body = """
                                    Επισυνάπτεται το τρέχον πρότυπο.

                                    Άλλαξέ το και στείλ' το πίσω στον εαυτό σου· μετά,
                                    από την οθόνη «Πρότυπο προσφοράς» πάτα
                                    «Αντικατάσταση από αρχείο».
                                """.trimIndent(),
                                attachment = file,
                                attachmentName = file.name,
                            )
                            if (settings.sendMethod == SendMethod.GOOGLE) {
                                GmailSender.send(authorizer.accessToken(), message)
                            } else {
                                MailSender.send(smtp, message)
                            }
                            to
                        }
                        busy = null
                        result.onSuccess {
                            Toast.makeText(context, "Στάλθηκε στο $it", Toast.LENGTH_LONG).show()
                        }.onFailure {
                            Toast.makeText(context, "Απέτυχε: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Αποστολή στο email μου", maxLines = 1) }

            OutlinedButton(
                enabled = busy == null,
                onClick = { picker.launch(arrayOf(DriveClient.DOCX_MIME)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Αντικατάσταση από αρχείο", maxLines = 1) }

            OutlinedButton(
                enabled = busy == null,
                onClick = {
                    scope.launch {
                        busy = "Δημιουργία προεπισκόπησης…"
                        runCatching {
                            val drive = DriveClient(authorizer.accessToken())
                            renderSamplePdf(context, drive, settings)
                        }.onSuccess { preview = it }
                            .onFailure {
                                Toast.makeText(context, "Απέτυχε: ${it.message}", Toast.LENGTH_LONG).show()
                            }
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

            val file = preview
            if (file != null) {
                Box(Modifier.fillMaxSize()) { PdfPreview(file) }
            }
        }
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
    <<[Οδός / Περιοχή]>> · <<[Είδος]>> · <<[Ημερομηνία]>> · <<[Γενικό Σύνολο Live]>>
    Γραμμή χώρου: <<[Περιγραφή Χώρου]>> · <<[Επιφάνεια (τ.μ.)]>> · <<[Τιμή Μονάδος]>> · <<[Σύνολο Γραμμής]>>
    Σημείωση: <<[Κείμενο]>>
""".trimIndent()
