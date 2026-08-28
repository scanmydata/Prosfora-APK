package gr.prosfora.app.ui.offers

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gr.prosfora.app.BuildConfig
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.sync.DriveSyncCoordinator
import gr.prosfora.app.update.UpdateChecker
import gr.prosfora.app.util.reason
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

private val BrandGreen = androidx.compose.ui.graphics.Color(0xFF2E7D32)

@Composable
fun UpdateDialog(release: UpdateChecker.Release, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        icon = {
            Icon(
                Icons.Default.SystemUpdate,
                contentDescription = null,
                tint = BrandGreen,
                modifier = Modifier.size(34.dp),
            )
        },
        title = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Νέα έκδοση διαθέσιμη", fontWeight = FontWeight.Bold)
                Text(
                    release.tag,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(14.dp),
                        )
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Download, contentDescription = null, tint = BrandGreen)
                    Column(Modifier.weight(1f)) {
                        Text("Ενημέρωση εφαρμογής", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Η νέα έκδοση θα κατέβει και θα ανοίξει ο εγκαταστάτης του Android.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (!downloading) {
                    Text(
                        release.notes.take(900).ifBlank { "Βελτιώσεις και διορθώσεις στη λειτουργία της εφαρμογής." },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                        Text("Λήψη ενημέρωσης…", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !downloading, onClick = {
                downloading = true
                scope.launch {
                    runCatching {
                        val apk = UpdateChecker.download(context, release)
                        UpdateChecker.install(context, apk)
                    }.onFailure {
                        Toast.makeText(context, "Αποτυχία λήψης: ${it.reason()}", Toast.LENGTH_LONG).show()
                    }
                    downloading = false
                    onDismiss()
                }
            }) {
                Text("Ενημέρωση τώρα", color = BrandGreen, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = onDismiss) { Text("Αργότερα") }
        },
    )
}

/** Το Refresh κάνει πλέον και συγχρονισμό και έλεγχο νέας έκδοσης. */
@Composable
fun UpdateAction() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()
    val settings = remember { GoogleSettings(context) }

    var busy by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf<UpdateChecker.Release?>(null) }

    IconButton(
        enabled = !busy,
        onClick = {
            busy = true
            scope.launch {
                val syncJob = async {
                    runCatching {
                        DriveSyncCoordinator.sync(
                            context = context,
                            accessToken = authorizer.accessToken(),
                            syncSheet = settings.spreadsheetId?.isNotBlank() == true,
                        )
                    }
                }
                val updateJob = async { runCatching { UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME) } }
                val sync = syncJob.await()
                val release = updateJob.await().getOrNull()
                busy = false

                sync.onSuccess { result ->
                    val debtText = when (result.importedDebts.size) {
                        0 -> ""
                        1 -> " · 1 νέα οφειλή"
                        else -> " · ${result.importedDebts.size} νέες οφειλές"
                    }
                    Toast.makeText(context, "Συγχρονισμός ολοκληρώθηκε$debtText", Toast.LENGTH_SHORT).show()
                }.onFailure {
                    Toast.makeText(context, "Ο συγχρονισμός απέτυχε: ${it.reason()}", Toast.LENGTH_LONG).show()
                }
                if (release != null) available = release
            }
        },
    ) {
        if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(Icons.Default.Refresh, contentDescription = "Συγχρονισμός και έλεγχος ενημέρωσης")
    }

    available?.let { release -> UpdateDialog(release = release, onDismiss = { available = null }) }
}
