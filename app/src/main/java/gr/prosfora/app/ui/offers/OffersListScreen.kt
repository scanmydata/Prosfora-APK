package gr.prosfora.app.ui.offers

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gr.prosfora.app.data.db.OfferStatus
import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.asOfferDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OffersListScreen(
    viewModel: OffersViewModel,
    onOpenOffer: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val offers by viewModel.offers.collectAsState()
    val query by viewModel.query.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Προσφορές") },
                actions = {
                    UpdateAction()
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Ρυθμίσεις")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.createOffer(onOpenOffer) }) {
                Icon(Icons.Default.Add, contentDescription = "Νέα προσφορά")
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Αναζήτηση") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )

            if (offers.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank()) "Καμία προσφορά ακόμη" else "Κανένα αποτέλεσμα",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(offers, key = { it.offer.id }) { details ->
                        OfferRow(details) { onOpenOffer(details.offer.id) }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfferRow(details: OfferWithDetails, onClick: () -> Unit) {
    val offer = details.offer
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusDot(offer.status)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    offer.address.ifBlank { "(χωρίς διεύθυνση)" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (offer.kind.isNotBlank()) {
                    Text(offer.kind, style = MaterialTheme.typography.bodyMedium)
                }
                Text(
                    "${offer.dateEpochDay.asOfferDate()} · ${details.total.asMoney()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                offer.status.label,
                style = MaterialTheme.typography.labelMedium,
                color = statusColor(offer.status),
            )
        }
    }
}

@Composable
internal fun StatusDot(status: OfferStatus) {
    Surface(color = statusColor(status), shape = CircleShape, modifier = Modifier.size(12.dp)) {}
}

/** Ίδια με τα Format Rules του AppSheet: Ολοκληρώθηκε πράσινο, Σε επεξεργασία κόκκινο. */
internal fun statusColor(status: OfferStatus): Color = when (status) {
    OfferStatus.COMPLETED -> Color(0xFF1DB954)
    OfferStatus.IN_PROGRESS -> Color(0xFFD32F2F)
    OfferStatus.CREATED -> Color(0xFF9E9E9E)
}
