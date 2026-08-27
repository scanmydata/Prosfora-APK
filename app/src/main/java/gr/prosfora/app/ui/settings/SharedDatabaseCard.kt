package gr.prosfora.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import gr.prosfora.app.google.DriveClient
import gr.prosfora.app.google.DriveWorkspace
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.SheetsClient
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.sync.SheetSync
import gr.prosfora.app.util.reason
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Η κοινόχρηστη βάση: ένα Google Sheet που βλέπουν όλοι όσοι έχουν πρόσβαση σε
 * αυτό — ακριβώς όπως δούλευε το AppSheet. Ο διαμοιρασμός γίνεται με τα κανονικά
 * δικαιώματα του Google Drive, όχι με κάτι δικό μας.
 */
@Composable
fun SharedDatabaseSettings(
    autoSync: Boolean,
    onAutoSyncChange: (Boolean) -> Unit,
    lastSync: Long,
    syncing: String?,
    onSyncingChange: (String?) -> Unit,
    onSynced: () -> Unit,
    googleSettings: GoogleSettings,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()

    var connectedId by remember { mutableStateOf(googleSettings.spreadsheetId) }
    var connectedName by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var sharing by remember { mutableStateOf(false) }

    Text(
        "Τα δεδομένα ζουν σε ένα Google Sheet μέσα στον φάκελο " +
            "«${GoogleSettings.DRIVE_FOLDER_NAME}» του Drive σου. Μοίρασε τον φάκελο " +
            "και οι συνεργάτες σου βλέπουν τις ίδιες προσφορές και τα ίδια PDF.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    if (connectedId != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Default.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                connectedName ?: "Συνδεδεμένο",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = syncing == null,
            onClick = { picking = true },
            modifier = Modifier.weight(1f),
        ) { Text(if (connectedId == null) "Επιλογή" else "Αλλαγή", maxLines = 1) }

        OutlinedButton(
            enabled = syncing == null,
            onClick = {
                onSyncingChange("Δημιουργία…")
                scope.launch {
                    val result = runCatching {
                        val token = authorizer.accessToken()
                        SheetSync(context, SheetsClient(token), googleSettings)
                            .createSharedSheet(
                                "Προσφορές — βάση δεδομένων",
                                DriveClient(token),
                            )
                    }
                    onSyncingChange(null)
                    result.onSuccess { id ->
                        connectedId = id
                        connectedName = "Προσφορές — βάση δεδομένων"
                        onSynced()
                        Toast.makeText(
                            context,
                            "Δημιουργήθηκε στον φάκελο ${GoogleSettings.DRIVE_FOLDER_NAME}",
                            Toast.LENGTH_LONG,
                        ).show()
                    }.onFailure {
                        Toast.makeText(context, "Απέτυχε: ${it.reason()}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            modifier = Modifier.weight(1f),
        ) { Text("Νέο Sheet") }
    }

    if (connectedId != null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Αυτόματος συγχρονισμός", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            Switch(checked = autoSync, onCheckedChange = onAutoSyncChange)
        }

        Button(
            enabled = syncing == null,
            onClick = {
                onSyncingChange("Συγχρονισμός…")
                scope.launch {
                    val result = runCatching {
                        SheetSync(
                            context,
                            SheetsClient(authorizer.accessToken()),
                            googleSettings,
                        ).sync()
                    }
                    onSyncingChange(null)
                    result.onSuccess { report ->
                        onSynced()
                        Toast.makeText(context, report.summary, Toast.LENGTH_LONG).show()
                    }.onFailure {
                        Toast.makeText(context, "Απέτυχε: ${it.reason()}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (syncing != null) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(syncing, maxLines = 1)
            } else {
                Text("Συγχρονισμός τώρα", maxLines = 1)
            }
        }

        Button(
            onClick = { sharing = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.PersonAdd, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Πρόσκληση συντάκτη", maxLines = 1)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://docs.google.com/spreadsheets/d/$connectedId/edit"),
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
            ) { Text("Άνοιγμα Sheet") }

            TextButton(
                onClick = {
                    scope.launch {
                        runCatching {
                            val drive = DriveClient(authorizer.accessToken())
                            DriveWorkspace(drive, googleSettings).rootFolder()
                        }.onSuccess { folder ->
                            context.startActivity(
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://drive.google.com/drive/folders/$folder"),
                                ),
                            )
                        }.onFailure {
                            Toast.makeText(context, "Απέτυχε: ${it.reason()}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                modifier = Modifier.weight(1f),
            ) { Text("Άνοιγμα φακέλου") }
        }

        Text(
            if (lastSync > 0) {
                "Τελευταίος συγχρονισμός: " +
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(lastSync))
            } else {
                "Δεν έχει γίνει συγχρονισμός ακόμη"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    if (sharing) {
        ShareFolderDialog(googleSettings = googleSettings, onDismiss = { sharing = false })
    }

    if (picking) {
        SheetPickerDialog(
            googleSettings = googleSettings,
            onPick = { file ->
                googleSettings.spreadsheetId = file.id
                connectedId = file.id
                connectedName = file.name
                picking = false
                Toast.makeText(context, "Συνδέθηκε: ${file.name}", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { picking = false },
        )
    }
}
