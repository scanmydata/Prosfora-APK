package gr.prosfora.app.ui.offers

import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Email
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.mail.MailSender
import gr.prosfora.app.mail.OfferMail
import gr.prosfora.app.settings.SmtpSettingsStore
import kotlinx.coroutines.launch

/**
 * Αντίστοιχο του action «ΑΠΟΣΤΟΛΗ ΠΡΟΣΦΟΡΑΣ» του AppSheet: ενεργό μόνο όταν
 * υπάρχει email και η κατάσταση είναι «Ολοκληρώθηκε», με επιβεβαίωση πριν σταλεί.
 * Χωρίς το τέχνασμα του trigger column — στέλνει απευθείας.
 */
@Composable
fun SendOfferAction(details: OfferWithDetails, onSent: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { SmtpSettingsStore(context) }

    var confirming by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }

    IconButton(
        enabled = details.canSendEmail && !sending,
        onClick = { confirming = true },
    ) {
        if (sending) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(
                Icons.Default.Email,
                contentDescription = "Αποστολή προσφοράς",
                // Το ίδιο κεχριμπαρένιο με το format rule "EMAIL BUTTON" του AppSheet
                tint = if (details.canSendEmail) Color(0xFFFFB300) else Color.Unspecified,
            )
        }
    }

    if (confirming) {
        val settings = store.load()
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Αποστολή προσφοράς") },
            text = {
                Text(
                    buildString {
                        append("Θέλετε σίγουρα να στείλετε email στην διεύθυνση ")
                        append(details.offer.email)
                        append(";\n\nΘέμα: ")
                        append(OfferMail.subject(details))
                        if (!settings.isConfigured) {
                            append("\n\n⚠️ Δεν έχουν ρυθμιστεί τα στοιχεία SMTP — άνοιξε τις Ρυθμίσεις πρώτα.")
                        }
                    },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = settings.isConfigured,
                    onClick = {
                        confirming = false
                        sending = true
                        scope.launch {
                            val result = runCatching {
                                MailSender.send(
                                    settings,
                                    OfferMail.compose(details, settings, pdf = null),
                                )
                            }
                            sending = false
                            result.onSuccess {
                                Toast.makeText(context, "Το email στάλθηκε", Toast.LENGTH_SHORT).show()
                                onSent()
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    "Αποτυχία αποστολής: ${it.message}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                ) { Text("Αποστολή") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Άκυρο") }
            },
        )
    }
}
