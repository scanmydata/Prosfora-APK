package gr.prosfora.app.ui.offers

import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sms
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gr.prosfora.app.BuildConfig
import gr.prosfora.app.data.db.OfferStatus
import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.rememberGoogleAuthorizer
import gr.prosfora.app.sync.DriveSyncCoordinator
import gr.prosfora.app.ui.components.ConfirmDialog
import gr.prosfora.app.update.UpdateChecker
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.asOfferDate
import gr.prosfora.app.util.asSentStamp
import gr.prosfora.app.util.reason
import kotlinx.coroutines.launch

private val BrandGreen = Color(0xFF00E2A2)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OffersListScreen(viewModel: OffersViewModel, onMenu: () -> Unit, onOpenOffer: (String) -> Unit) {
    val offers by viewModel.offers.collectAsState()
    val query by viewModel.query.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val authorizer = rememberGoogleAuthorizer()
    val googleSettings = remember { GoogleSettings(context) }
    var refreshing by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<OfferWithDetails?>(null) }
    var update by remember { mutableStateOf<UpdateChecker.Release?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("Προσφορές") }, navigationIcon = { gr.prosfora.app.ui.MenuButton(onMenu) }, actions = { UpdateAction() }) },
        floatingActionButton = {
            FloatingActionButton(containerColor = BrandGreen, contentColor = Color.Black, onClick = { viewModel.createOffer(onOpenOffer) }) {
                Icon(Icons.Default.Add, contentDescription = "Νέα προσφορά")
            }
        },
    ) { innerPadding ->
        Column(Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Αναζήτηση") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            PullToRefreshBox(
                isRefreshing = refreshing,
                onRefresh = {
                    if (googleSettings.spreadsheetId == null) {
                        Toast.makeText(context, "Δεν έχει οριστεί κοινόχρηστο Sheet — δες τις Ρυθμίσεις", Toast.LENGTH_LONG).show()
                        return@PullToRefreshBox
                    }
                    refreshing = true
                    scope.launch {
                        val result = runCatching { DriveSyncCoordinator.sync(context, authorizer.accessToken(), syncSheet = true) }
                        val release = runCatching { UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME) }.getOrNull()
                        refreshing = false
                        result.onSuccess { sync ->
                            val message = buildString {
                                append(sync.sheetSummary ?: "Συγχρονισμός ολοκληρώθηκε")
                                if (sync.importedDebts.isNotEmpty()) append(" · ${sync.importedDebts.size} νέα οφειλή${if (sync.importedDebts.size == 1) "" else "ές"}")
                            }
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }.onFailure { Toast.makeText(context, "Ο συγχρονισμός απέτυχε: ${it.reason()}", Toast.LENGTH_LONG).show() }
                        if (release != null) update = release
                    }
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (offers.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(top = 96.dp), contentAlignment = Alignment.TopCenter) {
                        Text(if (query.isBlank()) "Καμία προσφορά ακόμη" else "Κανένα αποτέλεσμα", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(offers, key = { it.offer.id }) { details ->
                            OfferRow(details, onClick = { onOpenOffer(details.offer.id) }, onLongPress = { pendingDelete = details })
                        }
                    }
                }
            }
        }

        pendingDelete?.let { target ->
            ConfirmDialog(
                title = "Διαγραφή προσφοράς",
                message = "Θα διαγραφεί η «${target.offer.address.ifBlank { "χωρίς διεύθυνση" }}» με ${target.spaces.size} χώρους. Η διαγραφή συγχρονίζεται και στους υπόλοιπους χρήστες.",
                onConfirm = { viewModel.deleteOffer(target.offer.id); pendingDelete = null },
                onDismiss = { pendingDelete = null },
            )
        }
        update?.let { release -> UpdateDialog(release = release, onDismiss = { update = null }) }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun OfferRow(details: OfferWithDetails, onClick: () -> Unit, onLongPress: () -> Unit) {
    val offer = details.offer
    Card(Modifier.fillMaxWidth().combinedClickable(onClick = onClick, onLongClick = onLongPress)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatusDot(offer.status)
                Column(Modifier.weight(1f)) {
                    Text(offer.address.ifBlank { "(χωρίς διεύθυνση)" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (offer.kind.isNotBlank()) Text(offer.kind, style = MaterialTheme.typography.bodyMedium)
                    Text("${offer.dateEpochDay.asOfferDate()} · ${details.grandTotal.asMoney()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (offer.customExtraName.isNotBlank()) Text("${offer.customExtraName}: ${details.customExtraCost.asMoney()}", style = MaterialTheme.typography.labelSmall, color = BrandGreen, fontWeight = FontWeight.Bold)
                }
                Text(offer.status.label, style = MaterialTheme.typography.labelMedium, color = statusColor(offer.status))
            }
            val badges = buildList {
                offer.lastSentAt?.let { add(Triple(Icons.Default.Email, "Email", it)) }
                offer.notifiedAt?.let { at -> val viber = offer.notifiedVia.equals("VIBER", ignoreCase = true); add(Triple(if (viber) Icons.Default.Send else Icons.Default.Sms, if (viber) "Viber" else "SMS", at)) }
            }
            if (badges.isNotEmpty()) FlowRow(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) { badges.forEach { (icon, label, at) -> SentBadge(icon, label, at) } }
        }
    }
}

@Composable private fun SentBadge(icon: ImageVector, label: String, at: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = SentGreen, modifier = Modifier.size(14.dp))
        Text(" $label ${at.asSentStamp()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable internal fun StatusDot(status: OfferStatus) {
    Surface(color = statusColor(status), shape = CircleShape, modifier = Modifier.size(12.dp)) {}
}

internal fun statusColor(status: OfferStatus): Color = when (status) {
    OfferStatus.COMPLETED -> Color(0xFF1DB954)
    OfferStatus.IN_PROGRESS -> Color(0xFFD32F2F)
    OfferStatus.CREATED -> Color(0xFF9E9E9E)
}