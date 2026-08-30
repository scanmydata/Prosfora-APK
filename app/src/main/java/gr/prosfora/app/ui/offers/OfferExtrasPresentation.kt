package gr.prosfora.app.ui.offers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import gr.prosfora.app.data.db.OfferEntity
import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.ui.components.StableTextField
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.parseDecimal

private val ExtrasGreen = androidx.compose.ui.graphics.Color(0xFF00E2A2)

@Composable
internal fun OfferExtrasCard(details: OfferWithDetails, viewModel: OffersViewModel) {
    val offer = details.offer

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Πρόσθετα κόστη", style = MaterialTheme.typography.titleMedium)

            OfferExtraEditor(
                label = "Σκαλωσιά",
                enabled = offer.scaffolding,
                amount = offer.scaffoldingCost,
                onEnabled = { viewModel.updateOffer(offer.copy(scaffolding = it)) },
                onAmount = { viewModel.updateOffer(offer.copy(scaffoldingCost = it)) },
            )
            OfferExtraEditor(
                label = "Άδεια μικρής κλίμακας εργασιών",
                enabled = offer.permit,
                amount = offer.permitCost,
                onEnabled = { viewModel.updateOffer(offer.copy(permit = it)) },
                onAmount = { viewModel.updateOffer(offer.copy(permitCost = it)) },
            )

            HorizontalDivider()
            CustomExtraEditor(offer = offer) { name, amount ->
                viewModel.updateOffer(
                    offer.copy(
                        customExtraName = name,
                        customExtraCost = amount,
                    ),
                )
            }
        }
    }
}

@Composable
private fun OfferExtraEditor(
    label: String,
    enabled: Boolean,
    amount: Double,
    onEnabled: (Boolean) -> Unit,
    onAmount: (Double) -> Unit,
) {
    var amountText by remember(label, enabled, amount) {
        mutableStateOf(if (amount > 0.0) amount.toString().removeSuffix(".0") else "")
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = enabled, onCheckedChange = onEnabled)
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
        if (enabled) {
            StableTextField(
                value = amountText,
                onValueChange = {
                    amountText = it
                    onAmount(it.parseDecimal() ?: 0.0)
                },
                label = "Ποσό (€)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                debounceMillis = 0,
                modifier = Modifier.fillMaxWidth().padding(start = 48.dp),
            )
        }
    }
}

@Composable
private fun CustomExtraEditor(
    offer: OfferEntity,
    onChange: (String, Double) -> Unit,
) {
    var name by remember(offer.id) { mutableStateOf(offer.customExtraName) }
    var amountText by remember(offer.id) {
        mutableStateOf(if (offer.customExtraCost > 0.0) offer.customExtraCost.toString().removeSuffix(".0") else "")
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Νέο πρόσθετο κόστος", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        StableTextField(
            value = name,
            onValueChange = {
                name = it
                onChange(it.trim(), amountText.parseDecimal() ?: 0.0)
            },
            label = "Ονομασία",
            debounceMillis = 0,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StableTextField(
                value = amountText,
                onValueChange = {
                    amountText = it
                    onChange(name.trim(), it.parseDecimal() ?: 0.0)
                },
                label = "Ποσό (€)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                debounceMillis = 0,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                enabled = name.isNotBlank() || amountText.isNotBlank(),
                onClick = {
                    name = ""
                    amountText = ""
                    onChange("", 0.0)
                },
            ) {
                Icon(Icons.Default.Clear, contentDescription = "Καθαρισμός")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, contentDescription = null, tint = ExtrasGreen, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "Μπορείς να ορίσεις οποιαδήποτε επιπλέον χρέωση με όνομα και ποσό.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun OfferTotalsCard(details: OfferWithDetails, viewModel: OffersViewModel) {
    val offer = details.offer
    val extras = buildList {
        if (details.scaffoldingCost > 0.0) add("Σκαλωσιά" to details.scaffoldingCost)
        if (details.permitCost > 0.0) add("Άδεια μικρής κλίμακας εργασιών" to details.permitCost)
        if (details.customExtraCost > 0.0 && offer.customExtraName.isNotBlank()) add(offer.customExtraName to details.customExtraCost)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Σύνολα & ΦΠΑ", style = MaterialTheme.typography.titleMedium)

            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = offer.vatIncluded,
                    onCheckedChange = { viewModel.updateOffer(offer.copy(vatIncluded = it)) },
                )
                Text("Υπολογισμός ΦΠΑ 24%", style = MaterialTheme.typography.bodyLarge)
            }

            HorizontalDivider()

            TotalsLine("ΣΥΝΟΛΟ ΧΩΡΩΝ", details.linesTotal, bold = true)
            extras.forEach { (label, amount) -> TotalsLine(label, amount) }
            if (offer.vatIncluded) TotalsLine("ΦΠΑ 24%", details.vatAmount)
            HorizontalDivider()
            TotalsLine("ΓΕΝΙΚΟ ΣΥΝΟΛΟ", details.grandTotal, bold = true)
        }
    }
}

@Composable
private fun TotalsLine(label: String, amount: Double, bold: Boolean = false) {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontWeight = weight)
        Text(amount.asMoney(), style = MaterialTheme.typography.bodyMedium, fontWeight = weight, textAlign = TextAlign.End)
    }
}
