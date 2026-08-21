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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.SheetsClient
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.sync.SheetSync
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Η κοινόχρηστη βάση: ένα Google Sheet που βλέπουν όλοι όσοι έχουν πρόσβαση σε
 * αυτό — ακριβώς όπως δούλευε το AppSheet. Ο διαμοιρασμός γίνεται με τα κανονικά
 * δικαιώματα του Google Drive, όχι με κάτι δικό μας.
 */
@Composable
fun SharedDatabaseCard(
    sheetInput: String,
    onSheetInputChange: (String) -> Unit,
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
    val connectedId = googleSettings.spreadsheetId

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Κοινόχρηστη βάση", style = MaterialTheme.typography.titleMedium)
            Text(
                "Τα δεδομένα ζουν σε ένα Google Sheet. Όποιος έχει πρόσβαση σε αυτό " +
                    "βλέπει τις ίδιες προσφορές — μοίρασέ το από το Drive όπως κάθε αρχείο.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = sheetInput,
                onValueChange = onSheetInputChange,
                label = { Text("Σύνδεσμος ή ID του Sheet") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = syncing == null && SheetsClient.extractId(sheetInput) != null,
                    onClick = {
                        val id = SheetsClient.extractId(sheetInput) ?: return@Button
                        googleSettings.spreadsheetId = id
                        onSheetInputChange(id)
                        Toast.makeText(context, "Συνδέθηκε", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("Σύνδεση") }

                OutlinedButton(
                    enabled = syncing == null,
                    onClick = {
                        onSyncingChange("Δημιουργία…")
                        scope.launch {
                            val result = runCatching {
                                val sheets = SheetsClient(authorizer.accessToken())
                                SheetSync(context, sheets, googleSettings)
                                    .createSharedSheet("Προσφορές — βάση δεδομένων")
                            }
                            onSyncingChange(null)
                            result.onSuccess { id ->
                                onSheetInputChange(id)
                                onSynced()
                                Toast.makeText(context, "Δημιουργήθηκε νέο Sheet", Toast.LENGTH_SHORT).show()
                            }.onFailure {
                                Toast.makeText(context, "Απέτυχε: ${it.message}", Toast.LENGTH_LONG).show()
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
                                val sheets = SheetsClient(authorizer.accessToken())
                                SheetSync(context, sheets, googleSettings).sync()
                            }
                            onSyncingChange(null)
                            result.onSuccess { report ->
                                onSynced()
                                Toast.makeText(context, report.summary, Toast.LENGTH_LONG).show()
                            }.onFailure {
                                Toast.makeText(context, "Απέτυχε: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (syncing != null) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                        Text(syncing)
                    } else {
                        Text("Συγχρονισμός τώρα")
                    }
                }

                TextButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://docs.google.com/spreadsheets/d/$connectedId/edit"),
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Άνοιγμα στο Google Sheets") }

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
        }
    }
}
