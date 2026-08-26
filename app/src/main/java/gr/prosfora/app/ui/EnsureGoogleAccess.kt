package gr.prosfora.app.ui

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import gr.prosfora.app.google.DriveClient
import gr.prosfora.app.google.DriveWatch
import gr.prosfora.app.google.DriveWorkspace
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.SheetsClient
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.sync.SheetSync
import kotlinx.coroutines.launch

/**
 * Έγκριση Google με το άνοιγμα της εφαρμογής, και αναζήτηση βάσης που υπάρχει ήδη.
 *
 * Δύο προβλήματα λύνονται εδώ. Πρώτο: η έγκριση ζητιόταν την πρώτη φορά που
 * χρειαζόταν — συνήθως πατώντας «Κοινόχρηστη βάση» βαθιά μέσα στις ρυθμίσεις —
 * και έμοιαζε σαν να χαλάει κάτι.
 *
 * Δεύτερο: μετά από επανεγκατάσταση η εφαρμογή ξεκινούσε άδεια, ενώ η βάση και
 * ο φάκελος ήταν ήδη εκεί στο Drive. Το scope `drive.file` δίνει πρόσβαση στα
 * αρχεία που έφτιαξε η ίδια η εφαρμογή, και η σχέση αυτή επιβιώνει της
 * επανεγκατάστασης — οπότε φτάνει να τα ψάξει.
 */
@Composable
fun EnsureGoogleAccess() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { GoogleSettings(context) }
    val authorizer = rememberGoogleAuthorizer()

    var found by remember { mutableStateOf<Found?>(null) }
    var busy by remember { mutableStateOf(false) }

    // Android 13+: χωρίς αυτήν η ειδοποίηση για αλλαγές στο Drive δεν βγαίνει.
    // Ζητείται σιωπηλά μία φορά· αν ο χρήστης αρνηθεί, μένουν οι εντός εφαρμογής.
    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            settings.notifyDriveChanges
        ) {
            runCatching { notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
        }

        // Σιωπηλή αποτυχία: αν ο χρήστης πει όχι, συνεχίζει offline και θα
        // ξαναρωτηθεί όταν πράγματι χρειαστεί κάτι από τη Google
        val token = runCatching { authorizer.accessToken() }.getOrNull() ?: return@LaunchedEffect
        val drive = DriveClient(token)

        if (settings.spreadsheetId == null) {
            runCatching { discover(drive, settings) }
                .getOrNull()
                ?.let { found = it }
        }

        // Τι άλλαξε στον κοινόχρηστο φάκελο από τότε που κοιτάξαμε τελευταία
        runCatching { DriveWatch.refresh(context, drive, settings) }
    }

    found?.let { existing ->
        AlertDialog(
            onDismissRequest = { if (!busy) found = null },
            icon = { Icon(Icons.Default.CloudDownload, contentDescription = null) },
            title = { Text("Βρέθηκε βάση στο Drive") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Στον φάκελο «${GoogleSettings.DRIVE_FOLDER_NAME}» υπάρχει ήδη " +
                            "«${existing.sheetName}».",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Θέλεις να συνδεθεί η εφαρμογή με αυτήν; Οι προσφορές σου θα " +
                            "κατέβουν όπως ήταν.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        busy = true
                        settings.spreadsheetId = existing.sheetId
                        settings.folderId = existing.folderId
                        existing.templateId?.let { settings.templateFileId = it }
                        scope.launch {
                            val result = runCatching {
                                SheetSync(
                                    context,
                                    SheetsClient(authorizer.accessToken()),
                                    settings,
                                ).sync()
                            }
                            busy = false
                            found = null
                            result.onSuccess {
                                Toast.makeText(context, it.summary, Toast.LENGTH_LONG).show()
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    "Συνδέθηκε, αλλά ο συγχρονισμός απέτυχε: ${it.message}",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        }
                    },
                ) { Text(if (busy) "Σύνδεση…" else "Σύνδεση") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { found = null }) { Text("Όχι τώρα") }
            },
        )
    }
}

private data class Found(
    val folderId: String,
    val sheetId: String,
    val sheetName: String,
    val templateId: String?,
)

/**
 * Ψάχνει τον φάκελο της εφαρμογής για κοινόχρηστη βάση και πρότυπο.
 *
 * Αν βρεθούν πολλά φύλλα, προτιμάται αυτό με το όνομα που δίνει η εφαρμογή —
 * ο χρήστης μπορεί να έχει αφήσει και άλλα εκεί μέσα.
 */
private suspend fun discover(drive: DriveClient, settings: GoogleSettings): Found? {
    val workspace = DriveWorkspace(drive, settings)
    val folder = workspace.rootFolder()
    val sheets = workspace.spreadsheetsInFolder()
    if (sheets.isEmpty()) return null

    val preferred = sheets.firstOrNull { it.name.startsWith(DEFAULT_SHEET_NAME) } ?: sheets.first()
    val template = runCatching {
        drive.findInFolder(GoogleSettings.TEMPLATE_NAME, folder)
    }.getOrNull()

    return Found(
        folderId = folder,
        sheetId = preferred.id,
        sheetName = preferred.name,
        templateId = template?.id,
    )
}

private const val DEFAULT_SHEET_NAME = "Προσφορές"
