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
import kotlinx.coroutines.launch

/**
 * Κουμπί «έλεγχος για ενημέρωση» — ρωτάει τα GitHub Releases, κατεβάζει το APK
 * και ανοίγει τον installer.
 */
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
                    Toast.makeText(context, "Αποτυχία ελέγχου: ${it.message}", Toast.LENGTH_LONG).show()
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

    val release = available
    if (release != null) {
        AlertDialog(
            onDismissRequest = { available = null },
            title = { Text("Νέα έκδοση ${release.tag}") },
            text = { Text(release.notes.take(400).ifBlank { "Διαθέσιμη ενημέρωση." }) },
            confirmButton = {
                TextButton(onClick = {
                    available = null
                    busy = true
                    scope.launch {
                        val result = runCatching {
                            val apk = UpdateChecker.download(context, release)
                            UpdateChecker.install(context, apk)
                        }
                        busy = false
                        result.onFailure {
                            Toast.makeText(context, "Αποτυχία λήψης: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Ενημέρωση") }
            },
            dismissButton = {
                TextButton(onClick = { available = null }) { Text("Αργότερα") }
            },
        )
    }
}
