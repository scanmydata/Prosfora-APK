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
import gr.prosfora.app.update.UpdateChecker
import gr.prosfora.app.util.reason
import kotlinx.coroutines.launch

/**
 * Διάλογος νέας έκδοσης. Ξεχωριστός από το κουμπί, γιατί τον χρησιμοποιεί και
 * το pull-to-refresh της λίστας.
 */
@Composable
fun UpdateDialog(release: UpdateChecker.Release, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var downloading by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("Νέα έκδοση ${release.tag}") },
        text = {
            Text(
                if (downloading) "Γίνεται λήψη…"
                else release.notes.take(400).ifBlank { "Διαθέσιμη ενημέρωση." },
            )
        },
        confirmButton = {
            TextButton(
                enabled = !downloading,
                onClick = {
                    downloading = true
                    scope.launch {
                        val result = runCatching {
                            val apk = UpdateChecker.download(context, release)
                            UpdateChecker.install(context, apk)
                        }
                        downloading = false
                        result.onFailure {
                            Toast.makeText(
                                context,
                                "Αποτυχία λήψης: ${it.reason()}",
                                Toast.LENGTH_LONG,
                            ).show()
                        }
                        onDismiss()
                    }
                },
            ) { Text("Ενημέρωση") }
        },
        dismissButton = {
            TextButton(enabled = !downloading, onClick = onDismiss) { Text("Αργότερα") }
        },
    )
}

/** Κουμπί χειροκίνητου ελέγχου για ενημέρωση. */
@Composable
fun UpdateAction() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var available by remember { mutableStateOf<UpdateChecker.Release?>(null) }

    IconButton(
        enabled = !busy,
        onClick = {
            busy = true
            scope.launch {
                val result = runCatching {
                    UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
                }
                busy = false
                result.onSuccess { release ->
                    if (release == null) {
                        Toast.makeText(context, "Έχεις την τελευταία έκδοση", Toast.LENGTH_SHORT).show()
                    } else {
                        available = release
                    }
                }.onFailure {
                    Toast.makeText(context, "Αποτυχία ελέγχου: ${it.reason()}", Toast.LENGTH_LONG).show()
                }
            }
        },
    ) {
        if (busy) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(Icons.Default.Refresh, contentDescription = "Έλεγχος για ενημέρωση")
        }
    }

    available?.let { release ->
        UpdateDialog(release = release, onDismiss = { available = null })
    }
}
