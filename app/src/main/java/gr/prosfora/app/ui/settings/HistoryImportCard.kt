package gr.prosfora.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import gr.prosfora.app.data.HistoryImporter
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.reason
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
    var plan by remember { mutableStateOf<HistoryImporter.Plan?>(null) }
    var includeChanged by remember { mutableStateOf(true) }
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
                val parsed = HistoryImporter.parse(text)
                parsed to HistoryImporter.plan(context, parsed)
            }
            busy = null
            result.onSuccess { (parsed, comparison) ->
                bundle = parsed
                plan = comparison
                includeChanged = true
            }.onFailure {
                Toast.makeText(context, "Απέτυχε: ${it.reason()}", Toast.LENGTH_LONG).show()
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

    val comparison = plan
    if (bundle != null && comparison != null) {
        val found = bundle!!
        val ids = comparison.fresh + if (includeChanged) comparison.changed else emptySet()

        AlertDialog(
            onDismissRequest = { if (busy == null) { bundle = null; plan = null } },
            title = { Text("Εισαγωγή ιστορικού") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Από «${found.source}», έτη ${found.years}.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        """
                            • ${found.offers.size} προσφορές στο αρχείο
                            • ${found.spaces.size} γραμμές χώρων, ${found.notes.size} σημειώσεις
                            • συνολική αξία ${found.total.asMoney()}
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodySmall,
                    )

                    HorizontalDivider()

                    Text(
                        """
                            Νέες: ${comparison.fresh.size}
                            Άλλαξαν στο φύλλο: ${comparison.changed.size}
                            Ίδιες: ${comparison.unchanged}
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    if (comparison.changed.isNotEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = includeChanged,
                                onCheckedChange = { includeChanged = it },
                            )
                            Text(
                                "Ενημέρωση όσων άλλαξαν",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                        Text(
                            if (includeChanged) {
                                "Θα πάρουν το περιεχόμενο του φύλλου. Αν κάποια από " +
                                    "αυτές την πείραξες και μέσα από την εφαρμογή, εκείνες " +
                                    "οι αλλαγές θα χαθούν."
                            } else {
                                "Θα μπουν μόνο οι νέες· οι υπόλοιπες μένουν όπως είναι."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (found.approximateDates > 0) {
                        Text(
                            "${found.approximateDates} προσφορές δεν είχαν ημερομηνία μέσα " +
                                "στο φύλλο· μπήκε η χρονιά του φακέλου.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = busy == null && ids.isNotEmpty(),
                    onClick = {
                        busy = "Εισαγωγή…"
                        scope.launch {
                            val result = runCatching {
                                HistoryImporter.store(context, found, ids) { stage -> busy = stage }
                            }
                            busy = null
                            bundle = null
                            plan = null
                            result.onSuccess { written ->
                                done = "Γράφτηκαν $written προσφορές"
                                Toast.makeText(context, done, Toast.LENGTH_LONG).show()
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    "Απέτυχε: ${it.reason()}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                ) {
                    Text(if (ids.isEmpty()) "Τίποτα νέο" else "Εισαγωγή ${ids.size}")
                }
            },
            dismissButton = {
                TextButton(
                    enabled = busy == null,
                    onClick = { bundle = null; plan = null },
                ) { Text("Άκυρο") }
            },
        )
    }
}
