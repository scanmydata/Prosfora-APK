package gr.prosfora.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import gr.prosfora.app.google.GoogleSettings

/**
 * Πότε και με τι κείμενο ζητείται αξιολόγηση μετά την ολοκλήρωση μιας δουλειάς.
 */
@Composable
fun ReviewSettingsCard(googleSettings: GoogleSettings) {
    val context = LocalContext.current
    var days by remember { mutableStateOf(googleSettings.reviewDelayDays.toString()) }
    var link by remember { mutableStateOf(googleSettings.reviewLink) }
    var template by remember { mutableStateOf(googleSettings.reviewTemplate) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Αξιολογήσεις", style = MaterialTheme.typography.titleMedium)
            Text(
                "Μετά την ολοκλήρωση μιας δουλειάς, το αίτημα αξιολόγησης εμφανίζεται " +
                    "στη σελίδα «Δουλειές» όταν περάσουν οι μέρες που ορίζεις εδώ.",
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

            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("Σύνδεσμος αξιολόγησης") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Ο σύνδεσμος της Google περιέχει παραμέτρους που μπορεί να πάψουν να " +
                    "ισχύουν με τον καιρό. Αν κάποια στιγμή δεν ανοίγει, αντικατέστησέ τον εδώ.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = template,
                onValueChange = { template = it },
                label = { Text("Κείμενο αιτήματος") },
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Χρησιμοποίησε {χαιρετισμός}, {διεύθυνση}, {είδος} και {αξιολόγηση} " +
                    "για τον σύνδεσμο.",
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
            ) { Text("Αποθήκευση", maxLines = 1) }
        }
    }
}
