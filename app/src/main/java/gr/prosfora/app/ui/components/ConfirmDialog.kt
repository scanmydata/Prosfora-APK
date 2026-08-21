package gr.prosfora.app.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import gr.prosfora.app.ui.offers.DeleteRed

/**
 * Επιβεβαίωση πριν από μη αναστρέψιμη ενέργεια. Κάθε διαγραφή στο app περνάει
 * από εδώ — οι διαγραφές συγχρονίζονται στο κοινόχρηστο Sheet και επηρεάζουν
 * όλους όσους το μοιράζονται.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String = "Διαγραφή",
    confirmColor: Color = DeleteRed,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm() }) {
                Text(confirmLabel, color = confirmColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Άκυρο") }
        },
    )
}
