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
import androidx.core.content.ContextCompat
import gr.prosfora.app.BuildConfig
import gr.prosfora.app.google.DriveClient
import gr.prosfora.app.google.DriveWatch
import gr.prosfora.app.google.DriveWorkspace
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.sync.DriveAutoSyncWorker
import gr.prosfora.app.sync.DriveSyncCoordinator
import gr.prosfora.app.ui.offers.UpdateDialog
import gr.prosfora.app.update.UpdateChecker
import gr.prosfora.app.util.reason
import kotlinx.coroutines.launch

/**
 * Ό,τι πρέπει να γίνει μόλις ανοίξει η εφαρμογή.
 *
 * Η σειρά είναι σκόπιμα αυστηρή:
 * 1. ζητάμε την άδεια ειδοποιήσεων όπου χρειάζεται,
 * 2. ελέγχουμε για νέα έκδοση,
 * 3. μόνο όταν ολοκληρωθεί ο έλεγχος έκδοσης ξεκινά ο πλήρης συγχρονισμός.
 *
 * Έτσι δεν υπάρχει αγώνας ανάμεσα στο update-check, στο Drive sync και στην
 * άδεια POST_NOTIFICATIONS που μπορούσε να οδηγήσει σε χαμένο notification.
 */
@Composable
fun EnsureGoogleAccess() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { GoogleSettings(context) }
    val authorizer = rememberGoogleAuthorizer()

    var found by remember { mutableStateOf<Found?>(null) }
    var busy by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<UpdateChecker.Release?>(null) }
    var notificationPermissionReady by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                !settings.notifyDriveChanges ||
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }
    var startupSyncStarted by remember { mutableStateOf(false) }

    val notifications = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        notificationPermissionReady = true
    }

    fun startStartupSync() {
        if (startupSyncStarted) return
        startupSyncStarted = true
        scope.launch {
            val token = runCatching { authorizer.accessToken() }
                .onFailure {
                    Toast.makeText(
                        context,
                        "Δεν έγινε συγχρονισμός Google: ${it.reason()}",
                        Toast.LENGTH_LONG,
                    ).show()
                    // Η τοπική βάση κρατάει τα πάντα ώσπου να ξαναϋπάρξει
                    // δίκτυο· το work περιμένει σύνδεση και ανεβάζει μόνο του
                    DriveAutoSyncWorker.enqueueNow(context)
                }
                .getOrNull()
                ?: return@launch

            val drive = DriveClient(token)

            if (settings.spreadsheetId == null) {
                runCatching { discover(drive, settings) }
                    .getOrNull()
                    ?.let {
                        found = it
                        return@launch
                    }
            }

            if (settings.spreadsheetId != null) {
                runCatching {
                    DriveSyncCoordinator.sync(
                        context = context,
                        accessToken = token,
                        syncSheet = true,
                    )
                }.onFailure {
                    Toast.makeText(
                        context,
                        "Ο συγχρονισμός απέτυχε: ${it.reason()}",
                        Toast.LENGTH_LONG,
                    ).show()
                    DriveAutoSyncWorker.enqueueNow(context)
                }

                runCatching {
                    DriveWatch.refresh(context, drive, settings)
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            settings.notifyDriveChanges &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            notificationPermissionReady = true
        }
    }

    LaunchedEffect(notificationPermissionReady) {
        if (!notificationPermissionReady) return@LaunchedEffect

        val latest = runCatching {
            UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
        }.onFailure {
            // Δεν μπλοκάρουμε τον συγχρονισμό επειδή το GitHub δεν απάντησε.
        }.getOrNull()

        if (latest != null) {
            update = latest
        } else {
            startStartupSync()
        }
    }

    update?.let { release ->
        UpdateDialog(
            release = release,
            onDismiss = {
                update = null
                startStartupSync()
            },
        )
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
                                DriveSyncCoordinator.sync(
                                    context = context,
                                    accessToken = authorizer.accessToken(),
                                    syncSheet = true,
                                )
                            }
                            busy = false
                            found = null
                            result.onSuccess {
                                Toast.makeText(context, it.sheetSummary ?: "Συγχρονισμός ολοκληρώθηκε", Toast.LENGTH_LONG).show()
                            }.onFailure {
                                Toast.makeText(
                                    context,
                                    "Συνδέθηκε, αλλά ο συγχρονισμός απέτυχε: ${it.reason()}",
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

/** Ψάχνει τον φάκελο της εφαρμογής για κοινόχρηστη βάση και πρότυπο. */
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
