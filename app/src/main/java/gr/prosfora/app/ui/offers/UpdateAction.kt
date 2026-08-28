package gr.prosfora.app.ui.offers

import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import gr.prosfora.app.BuildConfig
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.sync.DriveSyncCoordinator
import gr.prosfora.app.update.UpdateChecker
import gr.prosfora.app.util.reason
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

@Composable
fun UpdateDialog(release: UpdateChecker.Release, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("Νέα έκδοση ${release.tag}") },
        text = { Text(if (downloading) "Γίνεται λήψη…" else release.notes.take(400).ifBlank { "Διαθέσιμη ενημέρωση." }) },
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
            }) { Text("Ενημέρωση") }
        },
        dismissButton = { TextButton(enabled = !downloading, onClick = onDismiss) { Text("Αργότερα") } },
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
                    Toast.makeText(
                        context,
                        "Συγχρονισμός ολοκληρώθηκε$debtText",
                        Toast.LENGTH_SHORT,
                    ).show()
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
