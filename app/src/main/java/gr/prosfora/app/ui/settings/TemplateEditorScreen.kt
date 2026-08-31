package gr.prosfora.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import gr.prosfora.app.doc.DocxTemplate
import gr.prosfora.app.doc.OfferPdf
import gr.prosfora.app.google.DriveClient
import gr.prosfora.app.google.DriveWorkspace
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.ui.components.ConfirmDialog
import gr.prosfora.app.util.reason
import kotlinx.coroutines.launch

/**
 * Επεξεργασία των κειμένων του προτύπου μέσα από την εφαρμογή.
 *
 * Αλλάζει **μόνο κείμενο**: τίτλοι, επικεφαλίδες πίνακα, στοιχεία επικοινωνίας,
 * λεζάντες. Εικόνες, χρώματα και διάταξη μένουν όπως είναι — γι' αυτά υπάρχει
 * το «Επεξεργασία στο Google Docs», που δίνει πλήρη έλεγχο.
 *
 * Οι παράγραφοι με `<<πεδία>>` επισημαίνονται: αν σβηστούν, το PDF βγαίνει με
 * κενά στη θέση των δεδομένων.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplateEditorScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { GoogleSettings(context) }
    val authorizer = rememberGoogleAuthorizer()

    var original by remember { mutableStateOf<ByteArray?>(null) }
    var paragraphs by remember { mutableStateOf<List<DocxTemplate.Paragraph>>(emptyList()) }
    val edits = remember { mutableStateMapOf<Int, String>() }
    var busy by remember { mutableStateOf<String?>("Φόρτωση προτύπου…") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmSave by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching {
            val drive = DriveClient(authorizer.accessToken())
            val docx = OfferPdf.fetchTemplateDocx(context, drive, settings)
            docx to DocxTemplate.extractParagraphs(docx)
        }.onSuccess { (docx, list) ->
            original = docx
            paragraphs = list
        }.onFailure { error = it.message }
        busy = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Επεξεργασία προτύπου") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Πίσω")
                    }
                },
            )
        },
        floatingActionButton = {
            if (edits.isNotEmpty() && busy == null) {
                ExtendedFloatingActionButton(
                    onClick = { confirmSave = true },
                    icon = { Icon(Icons.Default.Save, contentDescription = null) },
                    text = { Text("Αποθήκευση (${edits.size})") },
                )
            }
        },
    ) { padding ->
        when {
            busy != null -> Box(
                Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(busy!!)
                }
            }

            error != null -> Box(
                Modifier.fillMaxSize().padding(padding).padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Δεν φορτώθηκε το πρότυπο: $error")
            }

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                // Χώρος για το κουμπί αποθήκευσης
                contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 112.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Text(
                            "Αλλάζεις μόνο κείμενα. Οι γραμμές με «<<…>>» περιέχουν πεδία " +
                                "που συμπληρώνονται αυτόματα — μην τα σβήσεις. Για εικόνες " +
                                "και διάταξη, χρησιμοποίησε το Google Docs.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                items(paragraphs, key = { it.index }) { paragraph ->
                    val value = edits[paragraph.index] ?: paragraph.text
                    Column {
                        OutlinedTextField(
                            value = value,
                            onValueChange = { updated ->
                                if (updated == paragraph.text) {
                                    edits.remove(paragraph.index)
                                } else {
                                    edits[paragraph.index] = updated
                                }
                            },
                            label = { Text("Γραμμή ${paragraph.index + 1}") },
                            minLines = 1,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (paragraph.hasPlaceholder) {
                            Text(
                                "Περιέχει αυτόματο πεδίο",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmSave) {
        val broken = paragraphs.count { paragraph ->
            val edited = edits[paragraph.index]
            paragraph.hasPlaceholder && edited != null &&
                !edited.contains("<<") && !edited.contains(">>")
        }
        ConfirmDialog(
            title = "Αποθήκευση προτύπου",
            message = buildString {
                append("Θα ενημερωθούν ${edits.size} γραμμές στο πρότυπο του Drive.")
                if (broken > 0) {
                    append("\n\n⚠️ Σε $broken γραμμές έχουν αφαιρεθεί τα αυτόματα πεδία. ")
                    append("Εκεί το PDF θα βγει χωρίς δεδομένα.")
                }
            },
            confirmLabel = "Αποθήκευση",
            confirmColor = MaterialTheme.colorScheme.primary,
            onConfirm = {
                val source = original ?: return@ConfirmDialog
                busy = "Αποθήκευση…"
                scope.launch {
                    val result = runCatching {
                        val updated = DocxTemplate.applyParagraphEdits(source, edits.toMap())
                        val drive = DriveClient(authorizer.accessToken())
                        settings.templateFileId?.let { old -> runCatching { drive.delete(old) } }
                        settings.templateFileId = null
                        val folder = DriveWorkspace(drive, settings).rootFolder()
                        drive.upload(
                            name = GoogleSettings.TEMPLATE_NAME,
                            bytes = updated,
                            mimeType = DriveClient.DOCX_MIME,
                            parentId = folder,
                            convertToGoogleDoc = true,
                        ).also { settings.templateFileId = it }
                        updated
                    }
                    busy = null
                    result.onSuccess { updated ->
                        original = updated
                        paragraphs = DocxTemplate.extractParagraphs(updated)
                        edits.clear()
                        Toast.makeText(context, "Το πρότυπο ενημερώθηκε", Toast.LENGTH_LONG).show()
                    }.onFailure {
                        Toast.makeText(context, "Απέτυχε: ${it.reason()}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { confirmSave = false },
        )
    }
}
