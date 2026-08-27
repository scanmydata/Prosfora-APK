package gr.prosfora.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import gr.prosfora.app.google.DriveClient
import gr.prosfora.app.google.DriveWorkspace
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.ConnectLink
import gr.prosfora.app.google.SendMethod
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.mail.GmailSender
import gr.prosfora.app.mail.MailSender
import gr.prosfora.app.message.MessageTemplates
import gr.prosfora.app.settings.SmtpSettingsStore
import gr.prosfora.app.ui.components.ConfirmDialog
import gr.prosfora.app.ui.offers.DeleteRed
import gr.prosfora.app.util.reason
import kotlinx.coroutines.launch

/**
 * Πρόσκληση συνεργάτη στον φάκελο «Προσφορές».
 *
 * Μοιράζεται ο **φάκελος**, όχι μεμονωμένα αρχεία: έτσι ο νέος συντάκτης παίρνει
 * με μία κίνηση τη βάση, το πρότυπο και τα PDF, και ό,τι δημιουργηθεί αργότερα
 * κληρονομεί αυτόματα την ίδια πρόσβαση.
 */
@Composable
fun ShareFolderDialog(
    googleSettings: GoogleSettings,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()

    var folderId by remember { mutableStateOf<String?>(null) }
    var people by remember { mutableStateOf<List<DriveClient.Collaborator>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var canEdit by remember { mutableStateOf(true) }
    var sendInvite by remember { mutableStateOf(true) }
    var pendingInvite by remember { mutableStateOf<String?>(null) }
    var pendingRevoke by remember { mutableStateOf<DriveClient.Collaborator?>(null) }

    suspend fun reload() {
        runCatching {
            val drive = DriveClient(authorizer.accessToken())
            val id = DriveWorkspace(drive, googleSettings).rootFolder()
            folderId = id
            drive.collaborators(id)
        }.onSuccess { people = it }
            .onFailure {
                Toast.makeText(context, "Δεν διαβάστηκαν οι συνεργάτες: ${it.reason()}", Toast.LENGTH_LONG).show()
            }
    }

    LaunchedEffect(Unit) {
        reload()
        loading = false
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Διαμοιρασμός φακέλου") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Ο φάκελος «${GoogleSettings.DRIVE_FOLDER_NAME}» περιέχει τη βάση, το " +
                        "πρότυπο και τα PDF. Όποιος προσκληθεί τα βλέπει όλα.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email συνεργάτη") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = canEdit,
                        onClick = { canEdit = true },
                        label = { Text("Επεξεργασία", maxLines = 1) },
                    )
                    FilterChip(
                        selected = !canEdit,
                        onClick = { canEdit = false },
                        label = { Text("Μόνο ανάγνωση", maxLines = 1) },
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = sendInvite, onCheckedChange = { sendInvite = it })
                    Text(
                        "Email με σύνδεσμο αυτόματης σύνδεσης",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
                Text(
                    "Το Google στέλνει τη δική του ειδοποίηση πρόσβασης. Αυτό είναι " +
                        "ένα δεύτερο, δικό μας email: πατώντας τον σύνδεσμο, η εφαρμογή " +
                        "του συνεργάτη συνδέεται μόνη της με τη βάση και τον φάκελο.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Button(
                    enabled = !busy && email.trim().contains("@"),
                    onClick = { pendingInvite = email.trim() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Πρόσκληση", maxLines = 1)
                }

                HorizontalDivider()
                Text("Έχουν πρόσβαση", style = MaterialTheme.typography.labelLarge)

                when {
                    loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    people.isEmpty() -> Text(
                        "Κανείς άλλος προς το παρόν.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 200.dp)) {
                        items(people, key = { it.permissionId }) { person ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(person.email, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        when {
                                            person.isOwner -> "Ιδιοκτήτης"
                                            person.role == DriveClient.ROLE_WRITER -> "Επεξεργασία"
                                            else -> "Μόνο ανάγνωση"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (!person.isOwner) {
                                    IconButton(onClick = { pendingRevoke = person }) {
                                        Icon(Icons.Default.Delete, "Αφαίρεση", tint = DeleteRed)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text("Κλείσιμο") }
        },
    )

    // Ο διαμοιρασμός δίνει σε τρίτον πρόσβαση σε πραγματικά δεδομένα πελατών,
    // οπότε ζητείται ρητή επιβεβαίωση με το τι ακριβώς θα μπορεί να κάνει.
    pendingInvite?.let { target ->
        ConfirmDialog(
            title = "Πρόσκληση συνεργάτη",
            message = "Θα σταλεί πρόσκληση στο $target με δικαίωμα " +
                (if (canEdit) "επεξεργασίας" else "ανάγνωσης") +
                " στον φάκελο «${GoogleSettings.DRIVE_FOLDER_NAME}»: προσφορές, στοιχεία " +
                "πελατών και PDF.",
            confirmLabel = "Αποστολή",
            confirmColor = MaterialTheme.colorScheme.primary,
            onConfirm = {
                busy = true
                scope.launch {
                    val result = runCatching {
                        val drive = DriveClient(authorizer.accessToken())
                        val id = folderId ?: DriveWorkspace(drive, googleSettings).rootFolder()
                        drive.share(
                            fileId = id,
                            email = target,
                            role = if (canEdit) DriveClient.ROLE_WRITER else DriveClient.ROLE_READER,
                        )
                        id
                    }
                    var mailError: String? = null
                    if (sendInvite) {
                        result.getOrNull()?.let { id ->
                            mailError = sendConnectEmail(context, authorizer.accessToken(), googleSettings, target, id)
                        }
                    }
                    result.onSuccess {
                        email = ""
                        reload()
                        Toast.makeText(
                            context,
                            mailError?.let { "Δόθηκε πρόσβαση, το email δεν στάλθηκε: $it" }
                                ?: "Στάλθηκε πρόσκληση στο $target",
                            Toast.LENGTH_LONG,
                        ).show()
                    }.onFailure {
                        Toast.makeText(context, "Απέτυχε: ${it.reason()}", Toast.LENGTH_LONG).show()
                    }
                    busy = false
                }
            },
            onDismiss = { pendingInvite = null },
        )
    }

    pendingRevoke?.let { person ->
        ConfirmDialog(
            title = "Αφαίρεση πρόσβασης",
            message = "Ο/η ${person.email} δεν θα βλέπει πλέον τον φάκελο.",
            confirmLabel = "Αφαίρεση",
            onConfirm = {
                busy = true
                scope.launch {
                    runCatching {
                        val drive = DriveClient(authorizer.accessToken())
                        val id = folderId ?: DriveWorkspace(drive, googleSettings).rootFolder()
                        drive.revoke(id, person.permissionId)
                    }.onSuccess {
                        reload()
                    }.onFailure {
                        Toast.makeText(context, "Απέτυχε: ${it.reason()}", Toast.LENGTH_LONG).show()
                    }
                    busy = false
                }
            },
            onDismiss = { pendingRevoke = null },
        )
    }
}

/**
 * Το email της πρόσκλησης, με τον σύνδεσμο που ρυθμίζει την εφαρμογή του
 * παραλήπτη. Επιστρέφει το μήνυμα σφάλματος αν δεν στάλθηκε, αλλιώς null — η
 * πρόσβαση έχει ήδη δοθεί, οπότε μια αποτυχία εδώ δεν ακυρώνει τίποτα.
 */
private suspend fun sendConnectEmail(
    context: android.content.Context,
    accessToken: String,
    googleSettings: GoogleSettings,
    to: String,
    folderId: String,
): String? {
    val sender = googleSettings.senderName.ifBlank { "Προσφορές" }
    val link = ConnectLink.build(
        spreadsheetId = googleSettings.spreadsheetId,
        folderId = folderId,
        templateId = googleSettings.templateFileId,
        from = sender,
    )
    val body = """
        Πρόσκληση από τον/την $sender.

        Έχεις πλέον πρόσβαση στον φάκελο «${GoogleSettings.DRIVE_FOLDER_NAME}» στο Google
        Drive: τη βάση των προσφορών, το πρότυπο και τα PDF.

        Για να ρυθμιστεί μόνη της η εφαρμογή στο κινητό σου, άνοιξε:
        $link

        Αν δεν έχεις ακόμη την εφαρμογή, κατέβασέ την από:
        https://github.com/scanmydata/Prosfora-APK/releases/latest
    """.trimIndent()
    val message = MailSender.Outgoing(
        to = to,
        subject = "Πρόσκληση στις Προσφορές — $sender",
        body = body,
        html = MessageTemplates.asHtml(body, link, "Ρύθμιση της εφαρμογής"),
    )
    return runCatching {
        if (googleSettings.sendMethod == SendMethod.GOOGLE) {
            GmailSender.send(accessToken, message)
        } else {
            MailSender.send(SmtpSettingsStore(context).load(), message)
        }
    }.exceptionOrNull()?.let { it.message ?: it.toString() }
}
