package gr.prosfora.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import gr.prosfora.app.google.ConnectLink
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.SheetsClient
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.sync.SheetSync
import gr.prosfora.app.util.reason
import kotlinx.coroutines.launch

/**
 * Ο συνεργάτης πάτησε τον σύνδεσμο της πρόσκλησης.
 *
 * Δεν εφαρμόζεται τίποτα σιωπηλά: η σύνδεση αλλάζει ποια βάση βλέπει η συσκευή,
 * οπότε ζητείται ρητή επιβεβαίωση — και λέγεται καθαρά ότι η τοπική βάση θα
 * συγχωνευθεί με την κοινόχρηστη.
 */
@Composable
fun ConnectInviteDialog(invite: ConnectLink.Invite, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { GoogleSettings(context) }
    val authorizer = rememberGoogleAuthorizer()
    var busy by remember { mutableStateOf(false) }

    val alreadyConnected = settings.spreadsheetId != null &&
        settings.spreadsheetId != invite.spreadsheetId

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        icon = { Icon(Icons.Default.CloudSync, contentDescription = null) },
        title = { Text("Σύνδεση με κοινόχρηστη βάση") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    invite.from?.let { "Ο/η $it σε προσκάλεσε στα δεδομένα του." }
                        ?: "Ήρθε πρόσκληση για κοινόχρηστα δεδομένα.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Η εφαρμογή θα συνδεθεί με το κοινόχρηστο Google Sheet και τον " +
                        "φάκελο του Drive. Χρειάζεται να έχεις ήδη πρόσβαση σε αυτά — " +
                        "ο σύνδεσμος από μόνος του δεν δίνει δικαιώματα.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (alreadyConnected) {
                    Text(
                        "Προσοχή: η συσκευή είναι ήδη συνδεδεμένη με άλλη βάση. Οι " +
                            "τοπικές προσφορές θα συγχωνευθούν με τις καινούριες.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    ConnectLink.applyTo(settings, invite)
                    scope.launch {
                        val result = runCatching {
                            SheetSync(
                                context,
                                SheetsClient(authorizer.accessToken()),
                                settings,
                            ).sync()
                        }
                        busy = false
                        result.onSuccess {
                            Toast.makeText(context, it.summary, Toast.LENGTH_LONG).show()
                        }.onFailure {
                            Toast.makeText(
                                context,
                                "Συνδέθηκε, αλλά ο συγχρονισμός απέτυχε: ${it.reason()}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        onDismiss()
                    }
                },
            ) {
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Σύνδεση…")
                    }
                } else {
                    Text("Σύνδεση")
                }
            }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Άκυρο") }
        },
    )
}
