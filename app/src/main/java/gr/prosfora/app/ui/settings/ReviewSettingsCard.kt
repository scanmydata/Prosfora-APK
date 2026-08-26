package gr.prosfora.app.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import gr.prosfora.app.google.GoogleSettings

/**
 * Πότε και με τι κείμενο ζητείται αξιολόγηση μετά την ολοκλήρωση μιας εργασίας.
 *
 * Ο σύνδεσμος της Google είναι τεράστιος, οπότε δεν τον δείχνουμε ολόκληρο:
 * εμφανίζεται ως «Πατήστε εδώ», ανοίγει για δοκιμή και αλλάζει από διάλογο.
 */
@Composable
fun ReviewSettings(googleSettings: GoogleSettings) {
    val context = LocalContext.current
    var days by remember { mutableStateOf(googleSettings.reviewDelayDays.toString()) }
    var link by remember { mutableStateOf(googleSettings.reviewLink) }
    var template by remember { mutableStateOf(googleSettings.reviewTemplate) }
    var editingLink by remember { mutableStateOf<String?>(null) }

    Text(
        "Μετά την ολοκλήρωση μιας εργασίας, το αίτημα αξιολόγησης εμφανίζεται " +
            "στη σελίδα «Εργασίες» όταν περάσουν οι μέρες που ορίζεις εδώ.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = days,
        onValueChange = { days = it.filter(Char::isDigit).take(3) },
        label = { Text("Μέρες αναμονής") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Σύνδεσμος αξιολόγησης", style = MaterialTheme.typography.labelLarge)
            Row(
                modifier = Modifier.clickable(enabled = link.isNotBlank()) {
                    runCatching {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(link))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }.onFailure {
                        Toast.makeText(context, "Δεν άνοιξε: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    if (link.isBlank()) "  Δεν έχει οριστεί" else "  Πατήστε εδώ για δοκιμή",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline,
                )
            }
            Text(
                "Στο email ο πελάτης βλέπει «Πατήστε εδώ», όχι ολόκληρη τη διεύθυνση. " +
                    "Στο SMS και στο Viber δεν γίνεται — εκεί φεύγει αναγκαστικά ο πλήρης σύνδεσμος.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { editingLink = link }) { Text("Αλλαγή συνδέσμου") }
        }
    }

    OutlinedTextField(
        value = template,
        onValueChange = { template = it },
        label = { Text("Κείμενο αιτήματος") },
        minLines = 6,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        "Χρησιμοποίησε {χαιρετισμός}, {διεύθυνση}, {είδος} και {αξιολόγηση} για τον σύνδεσμο.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Button(
        onClick = {
            googleSettings.reviewDelayDays = days.toIntOrNull() ?: 3
            googleSettings.reviewLink = link
            googleSettings.reviewTemplate = template
            Toast.makeText(context, "Αποθηκεύτηκε", Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Αποθήκευση") }

    editingLink?.let { draft ->
        var value by remember(draft) { mutableStateOf(draft) }
        AlertDialog(
            onDismissRequest = { editingLink = null },
            title = { Text("Σύνδεσμος αξιολόγησης") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        label = { Text("Διεύθυνση") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "Ο σύνδεσμος από την αναζήτηση της Google περιέχει προσωρινές " +
                            "παραμέτρους και κάποια στιγμή παύει να δουλεύει. Πιο μόνιμος " +
                            "είναι αυτός που δίνει το Προφίλ Επιχείρησης (g.page/r/…/review).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    link = value.trim()
                    googleSettings.reviewLink = link
                    editingLink = null
                }) { Text("Αποθήκευση") }
            },
            dismissButton = {
                TextButton(onClick = { editingLink = null }) { Text("Άκυρο") }
            },
        )
    }
}
