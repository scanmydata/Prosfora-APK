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
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.ui.components.ConfirmDialog
import gr.prosfora.app.ui.offers.DeleteRed
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
                Toast.makeText(context, "Δεν διαβάστηκαν οι συνεργάτες: ${it.message}", Toast.LENGTH_LONG).show()
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
                    }
                    result.onSuccess {
                        email = ""
                        reload()
                        Toast.makeText(context, "Στάλθηκε πρόσκληση στο $target", Toast.LENGTH_LONG).show()
                    }.onFailure {
                        Toast.makeText(context, "Απέτυχε: ${it.message}", Toast.LENGTH_LONG).show()
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
                        Toast.makeText(context, "Απέτυχε: ${it.message}", Toast.LENGTH_LONG).show()
                    }
                    busy = false
                }
            },
            onDismiss = { pendingRevoke = null },
        )
    }
}
