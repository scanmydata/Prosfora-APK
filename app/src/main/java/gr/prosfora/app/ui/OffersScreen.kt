package gr.prosfora.app.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gr.prosfora.app.R
import gr.prosfora.app.data.Offer
import gr.prosfora.app.data.OfferStatus
import gr.prosfora.app.data.SampleData
import java.time.format.DateTimeFormatter

private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OffersScreen() {
    var query by remember { mutableStateOf("") }
    val offers = remember(query) {
        SampleData.offers.filter {
            query.isBlank() ||
                it.customer.contains(query, ignoreCase = true) ||
                it.title.contains(query, ignoreCase = true)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.app_name)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { /* Φάση 4: φόρμα νέας εγγραφής */ }) {
                Icon(Icons.Default.Add, contentDescription = stringResource(R.string.add_offer))
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text(stringResource(R.string.search_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(offers, key = { it.id }) { OfferRow(it) }
            }
        }
    }
}

@Composable
private fun OfferRow(offer: Offer) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            StatusDot(offer.status)
            Column(modifier = Modifier.weight(1f)) {
                Text(offer.customer, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(offer.title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "${offer.date.format(dateFormat)} · ${"%.2f".format(offer.amount)} €",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun StatusDot(status: OfferStatus) {
    val color = when (status) {
        OfferStatus.COMPLETED -> Color(0xFF2E7D32)
        OfferStatus.IN_PROGRESS -> Color(0xFFC62828)
    }
    Surface(color = color, shape = CircleShape, modifier = Modifier.size(14.dp)) {}
}
