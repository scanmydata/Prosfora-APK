package gr.prosfora.app.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import gr.prosfora.app.debug.DebugLog
import gr.prosfora.app.google.GoogleSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Ο διακόπτης της καταγραφής και το παράθυρο ανάγνωσής της.
 *
 * Υπάρχει επειδή κάποιες αστοχίες ζουν μόνο πάνω στη συσκευή: το κείμενο που
 * γυρίζει το OCR φτιάχνεται τη στιγμή που ζητιέται και δεν αναπαράγεται από
 * αλλού. Χωρίς αυτό, το μόνο που έφτανε πίσω ήταν «καμία οφειλή δεν
 * αναγνωρίστηκε» — μια πρόταση από την οποία δεν βγαίνει διόρθωση, μόνο εικασία.
 */
@Composable
fun DiagnosticsSettings(settings: GoogleSettings) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(settings.debugLogging) }
    var showing by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Καταγραφή διαγνωστικών", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Γράφει σε τοπικό αρχείο τι ακριβώς διάβασε από κάθε παραστατικό",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = on,
            onCheckedChange = {
                on = it
                settings.debugLogging = it
                DebugLog.configure(context, it)
            },
        )
    }

    Text(
        "Το κείμενο των παραστατικών περιέχει ΑΦΜ, ονόματα και ποσά. Μένει στη " +
            "συσκευή και δεν στέλνεται πουθενά — εκτός αν το στείλεις εσύ.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { showing = true }) { Text("Προβολή") }
        TextButton(onClick = { shareLog(context) }) { Text("Αποστολή") }
        TextButton(
            onClick = {
                DebugLog.clear(context)
                Toast.makeText(context, "Η καταγραφή σβήστηκε", Toast.LENGTH_SHORT).show()
            },
        ) { Text("Καθαρισμός") }
    }

    BannedEmployees(settings)

    if (showing) LogViewer(onDismiss = { showing = false })
}

/**
 * Η λίστα των εργαζομένων που έχουν διαγραφεί «και από τη βάση».
 *
 * Ήταν αόρατη, και ό,τι έμπαινε μέσα της δεν έβγαινε ποτέ: οι αποκλεισμένοι
 * φαίνονταν κανονικά στην εφαρμογή αλλά δεν έφταναν ποτέ στο κοινόχρηστο
 * φύλλο. Πλέον η λίστα αδειάζει μόνη της με κάθε νέα μισθοδοσία — αλλά όποιος
 * δεν έχει καινούργια μισθοδοσία χρειάζεται και έναν διακόπτη με το χέρι.
 */
@Composable
private fun BannedEmployees(settings: GoogleSettings) {
    val context = LocalContext.current
    var banned by remember { mutableStateOf(settings.deletedEmployeeIds) }
    if (banned.isEmpty()) return

    Text(
        "Διαγραμμένοι εργαζόμενοι που δεν συγχρονίζονται: ${banned.size}",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(
        onClick = {
            settings.deletedEmployeeIds = emptySet()
            banned = emptySet()
            Toast.makeText(context, "Θα συγχρονιστούν όλοι στον επόμενο συγχρονισμό", Toast.LENGTH_SHORT).show()
        },
    ) { Text("Επαναφορά όλων") }
}

@Composable
private fun LogViewer(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("Φόρτωση…") }

    // Το αρχείο φτάνει το μισό μεγαμπάιτ· δεν διαβάζεται στο νήμα της οθόνης
    LaunchedEffect(Unit) {
        text = withContext(Dispatchers.IO) { DebugLog.read(context) }
            .ifBlank { "Κενό. Άνοιξε την καταγραφή και ξαναδοκίμασε την εισαγωγή." }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Καταγραφή") },
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                // Οι στήλες του OCR διαβάζονται μόνο με σταθερό πλάτος, και το
                // κείμενο δεν πρέπει να αναδιπλώνεται: η αναδίπλωση κρύβει
                // ακριβώς τα κενά που μας ενδιαφέρουν
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState()),
            )
        },
        confirmButton = {
            TextButton(
                onClick = {
                    copy(context, text)
                    Toast.makeText(context, "Αντιγράφηκε", Toast.LENGTH_SHORT).show()
                },
            ) { Text("Αντιγραφή") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Κλείσιμο") } },
    )
}

private fun copy(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("Καταγραφή Προσφορές", text))
}

/** Στέλνει το αρχείο όπως είναι, για να μη χαθεί τίποτα στην αντιγραφή. */
private fun shareLog(context: Context) {
    val log = DebugLog.file(context)
    if (!log.exists()) {
        Toast.makeText(context, "Δεν υπάρχει καταγραφή ακόμη", Toast.LENGTH_SHORT).show()
        return
    }
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", log)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                "Αποστολή καταγραφής",
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Toast.makeText(context, "Δεν στάλθηκε: ${it.message}", Toast.LENGTH_LONG).show()
    }
}
