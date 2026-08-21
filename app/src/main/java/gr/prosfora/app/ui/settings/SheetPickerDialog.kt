package gr.prosfora.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gr.prosfora.app.google.DriveClient
import gr.prosfora.app.google.DriveWorkspace
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.rememberGoogleAuthorizer

/**
 * Επιλογή του κοινόχρηστου Sheet από λίστα, αντί για χειροκίνητη πληκτρολόγηση
 * αναγνωριστικού.
 *
 * Δείχνει πρώτα ό,τι βρίσκεται στον φάκελο «Προσφορές» και, αν δεν φτάνει, όλα
 * τα spreadsheets στα οποία έχει πρόσβαση το app. Δεν βλέπει ολόκληρο το Drive:
 * το scope `drive.file` δίνει πρόσβαση μόνο σε αρχεία που δημιούργησε ή που
 * άνοιξε ρητά ο χρήστης με αυτή την εφαρμογή.
 */
@Composable
fun SheetPickerDialog(
    googleSettings: GoogleSettings,
    onPick: (DriveClient.DriveFile) -> Unit,
    onDismiss: () -> Unit,
) {
    val authorizer = rememberGoogleAuthorizer()

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var inFolder by remember { mutableStateOf<List<DriveClient.DriveFile>>(emptyList()) }
    var elsewhere by remember { mutableStateOf<List<DriveClient.DriveFile>>(emptyList()) }

    LaunchedEffect(Unit) {
        runCatching {
            val drive = DriveClient(authorizer.accessToken())
            val workspace = DriveWorkspace(drive, googleSettings)
            val folder = workspace.spreadsheetsInFolder()
            val all = workspace.allVisibleSpreadsheets()
            val folderIds = folder.map { it.id }.toSet()
            folder to all.filterNot { it.id in folderIds }
        }.onSuccess { (folder, others) ->
            inFolder = folder
            elsewhere = others
        }.onFailure { error = it.message }
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Επιλογή κοινόχρηστης βάσης") },
        text = {
            when {
                loading -> Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                }

                error != null -> Text("Δεν διαβάστηκε η λίστα: $error")

                inFolder.isEmpty() && elsewhere.isEmpty() -> Text(
                    "Δεν βρέθηκε κανένα spreadsheet προσβάσιμο από την εφαρμογή. " +
                        "Πάτα «Νέο Sheet» για να δημιουργηθεί ένα μέσα στον φάκελο " +
                        "«${GoogleSettings.DRIVE_FOLDER_NAME}».",
                )

                else -> LazyColumn(Modifier.heightIn(max = 340.dp)) {
                    if (inFolder.isNotEmpty()) {
                        item { SectionLabel("Στον φάκελο ${GoogleSettings.DRIVE_FOLDER_NAME}") }
                        items(inFolder, key = { it.id }) { SheetRow(it, onPick) }
                    }
                    if (elsewhere.isNotEmpty()) {
                        item { SectionLabel("Αλλού στο Drive") }
                        items(elsewhere, key = { it.id }) { SheetRow(it, onPick) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Κλείσιμο") } },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Column {
        HorizontalDivider()
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

@Composable
private fun SheetRow(file: DriveClient.DriveFile, onPick: (DriveClient.DriveFile) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onPick(file) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.padding(start = 12.dp)) {
            Text(file.name, style = MaterialTheme.typography.bodyMedium)
            file.modifiedTime?.let {
                Text(
                    "Τελευταία αλλαγή: ${it.take(10)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
