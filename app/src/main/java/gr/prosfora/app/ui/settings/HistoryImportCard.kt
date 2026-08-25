package gr.prosfora.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import gr.prosfora.app.data.HistoryImporter
import gr.prosfora.app.ui.components.ConfirmDialog
import gr.prosfora.app.util.asMoney
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Εισαγωγή του παλιού αρχείου προσφορών, από το JSON που παράγει το
 * `migration/import_history.py` στον υπολογιστή.
 *
 * Δείχνει πρώτα τι βρήκε μέσα και εισάγει μόνο μετά από επιβεβαίωση: μιλάμε για
 * εκατοντάδες προσφορές που θα φύγουν και στο κοινόχρηστο Sheet.
 */
@Composable
fun HistoryImportSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var busy by remember { mutableStateOf<String?>(null) }
    var bundle by remember { mutableStateOf<HistoryImporter.Bundle?>(null) }
    var done by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        busy = "Ανάγνωση αρχείου…"
        scope.launch {
            val result = runCatching {
                val text = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use {
                        it.bufferedReader().readText()
                    } ?: error("Δεν διαβάστηκε το αρχείο")
                }
                HistoryImporter.parse(text)
            }
            busy = null
            result.onSuccess { bundle = it }.onFailure {
                Toast.makeText(context, "Απέτυχε: ${it.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Text(
        "Οι παλιές προσφορές ζουν σε αρχεία Excel στον υπολογιστή. Το script " +
            "«migration/import_history.py» τις μαζεύει σε ένα αρχείο .json — " +
            "διάλεξέ το εδώ.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Button(
        enabled = busy == null,
        onClick = { picker.launch(arrayOf("application/json", "text/plain", "*/*")) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy != null) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text(busy!!, maxLines = 1)
        } else {
            Text("Εισαγωγή ιστορικού από αρχείο", maxLines = 1)
        }
    }

    Text(
        "Η εισαγωγή μπορεί να ξανατρέξει χωρίς φόβο: οι ίδιες προσφορές " +
            "ενημερώνονται, δεν διπλασιάζονται. Μετά, κάνε συγχρονισμό για να " +
            "ανέβουν στο κοινόχρηστο Sheet.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    done?.let { summary ->
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(Modifier.padding(12.dp)) {
                Text("Ολοκληρώθηκε", style = MaterialTheme.typography.labelLarge)
                Text(summary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    bundle?.let { found ->
        ConfirmDialog(
            title = "Εισαγωγή ${found.offers.size} προσφορών",
            message = buildString {
                append("Από «${found.source}», έτη ${found.years}.\n\n")
                append("• ${found.offers.size} προσφορές (${found.completed} ολοκληρωμένες)\n")
                append("• ${found.spaces.size} γραμμές χώρων\n")
                append("• ${found.notes.size} σημειώσεις\n")
                append("• συνολική αξία ${found.total.asMoney()}\n")
                if (found.approximateDates > 0) {
                    append("\n${found.approximateDates} προσφορές δεν είχαν ημερομηνία μέσα ")
                    append("στο φύλλο· μπήκε η χρονιά του φακέλου.")
                }
                append("\n\nΘα προστεθούν στην τοπική βάση και θα φύγουν στο ")
                append("κοινόχρηστο Sheet με τον επόμενο συγχρονισμό.")
            },
            confirmLabel = "Εισαγωγή",
            confirmColor = MaterialTheme.colorScheme.primary,
            onConfirm = {
                busy = "Εισαγωγή…"
                scope.launch {
                    val result = runCatching {
                        HistoryImporter.store(context, found) { stage -> busy = stage }
                    }
                    busy = null
                    result.onSuccess {
                        done = "${found.offers.size} προσφορές, ${found.spaces.size} χώροι, " +
                            "${found.notes.size} σημειώσεις"
                        Toast.makeText(context, "Το ιστορικό μπήκε", Toast.LENGTH_LONG).show()
                    }.onFailure {
                        Toast.makeText(context, "Απέτυχε: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { bundle = null },
        )
    }
}
