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
import gr.prosfora.app.data.db.NoteEntity
import gr.prosfora.app.data.db.OfferEntity
import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.data.db.SpaceEntity
import gr.prosfora.app.doc.DocxTemplate
import gr.prosfora.app.doc.OfferPdf
import gr.prosfora.app.google.DriveClient
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.ui.pdf.PdfPreview
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Το πρότυπο του PDF: επεξεργασία στο Google Docs και προεπισκόπηση της
 * τυπωμένης σελίδας A4, με δείγμα δεδομένων ώστε να φαίνεται πώς θα βγει.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()
    val settings = remember { GoogleSettings(context) }

    var busy by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<File?>(null) }
    var templateId by remember { mutableStateOf(settings.templateFileId) }

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
    val rendered = DocxTemplate.render(templateDocx, SAMPLE)

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

private val SAMPLE: OfferWithDetails by lazy {
    val offer = OfferEntity(
        id = "preview",
        address = "Δείγμα 12, Αθήνα",
        dateEpochDay = LocalDate.now().toEpochDay(),
        kind = "Διαμέρισμα",
    )
    OfferWithDetails(
        offer = offer,
        spacesRaw = listOf(
            SpaceEntity(offerId = offer.id, description = "Σαλόνι", area = 45.0, unitPrice = 4.8, position = 0),
            SpaceEntity(offerId = offer.id, description = "Κουζίνα", area = 18.5, unitPrice = 4.8, position = 1),
            SpaceEntity(offerId = offer.id, description = "Πόρτες ριπολίνα", area = 4.0, unitPrice = 55.0, position = 2),
        ),
        notesRaw = listOf(
            NoteEntity(offerId = offer.id, text = "Στην προσφορά δεν περιλαμβάνεται ο ΦΠΑ τιμολογίου.", position = 0),
            NoteEntity(offerId = offer.id, text = "Η προσφορά περιλαμβάνει την εργασία και τα υλικά.", position = 1),
        ),
    )
}

private val PLACEHOLDER_HELP = """
    <<[Οδός / Περιοχή]>> · <<[Είδος]>> · <<[Ημερομηνία]>> · <<[Γενικό Σύνολο Live]>>
    Γραμμή χώρου: <<[Περιγραφή Χώρου]>> · <<[Επιφάνεια (τ.μ.)]>> · <<[Τιμή Μονάδος]>> · <<[Σύνολο Γραμμής]>>
    Σημείωση: <<[Κείμενο]>>
""".trimIndent()
