package gr.prosfora.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Handyman
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gr.prosfora.app.BuildConfig
import gr.prosfora.app.ui.offers.EditBlue
import gr.prosfora.app.ui.offers.EmailAmber
import gr.prosfora.app.ui.offers.SentGreen

/**
 * Οι σελίδες που φτάνει κανείς από το πλαϊνό μενού.
 *
 * Πρώτα τα στατιστικά: είναι η αρχική οθόνη, η εικόνα της χρονιάς με μια ματιά.
 * Οι υπόλοιπες μπαίνουν με τη σειρά της ροής — προσφορά, εργασία, αρχείο.
 */
enum class TopDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val tint: Color?,
) {
    STATS(ROUTE_STATS, "Στατιστικά", Icons.Default.BarChart, EditBlue),
    OFFERS(ROUTE_LIST, "Προσφορές", Icons.Default.Description, EmailAmber),
    JOBS(ROUTE_JOBS, "Εργασίες", Icons.Default.Handyman, SentGreen),
    ARCHIVE(ROUTE_ARCHIVE, "Αρχείο PDF", Icons.Default.PictureAsPdf, null),
    SETTINGS(ROUTE_SETTINGS, "Ρυθμίσεις", Icons.Default.Settings, null),
}

@Composable
fun AppDrawer(current: String?, onSelect: (TopDestination) -> Unit) {
    ModalDrawerSheet {
        Column(
            Modifier.padding(start = 24.dp, top = 28.dp, end = 24.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                "ΠΡΟΣΦΟΡΕΣ",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "tovapsimo.gr",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider()

        TopDestination.entries.forEach { destination ->
            if (destination == TopDestination.SETTINGS) HorizontalDivider(Modifier.padding(vertical = 8.dp))
            NavigationDrawerItem(
                selected = current == destination.route,
                onClick = { onSelect(destination) },
                icon = {
                    Icon(
                        destination.icon,
                        contentDescription = null,
                        tint = destination.tint ?: androidx.compose.material3.LocalContentColor.current,
                    )
                },
                label = { Text(destination.label, maxLines = 1) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            )
        }

        Text(
            "Έκδοση ${BuildConfig.VERSION_NAME}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        )
    }
}

/** Το κουμπί που ανοίγει το μενού — μπαίνει σε κάθε σελίδα πρώτου επιπέδου. */
@Composable
fun MenuButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Default.Menu, contentDescription = "Μενού", modifier = Modifier.size(24.dp))
    }
}
