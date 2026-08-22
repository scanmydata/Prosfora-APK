package gr.prosfora.app.ui.pdf

import android.content.Intent
import android.widget.Toast
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import gr.prosfora.app.doc.OfferPdf
import gr.prosfora.app.ui.offers.OffersViewModel
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.asOfferDate
import java.io.File

/**
 * Αρχείο των παραγόμενων PDF, ομαδοποιημένο ανά έτος έκδοσης — η ίδια οργάνωση
 * που έχουν και οι φάκελοι στο Drive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfArchiveScreen(viewModel: OffersViewModel, onMenu: () -> Unit) {
    val context = LocalContext.current
    val offers by viewModel.offers.collectAsState()
    var preview by remember { mutableStateOf<File?>(null) }

    // Μόνο όσες προσφορές έχουν πράγματι παραχθεί σε PDF τοπικά
    val byYear = remember(offers) {
        offers.mapNotNull { details ->
            val file = OfferPdf.pdfFile(context, details)
            if (file.exists()) details to file else null
        }.groupBy { it.first.year }.toSortedMap(compareByDescending { it })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Αρχείο PDF") },
                navigationIcon = { gr.prosfora.app.ui.MenuButton(onMenu) },
            )
        },
    ) { padding ->
        if (byYear.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "Δεν έχει δημιουργηθεί κανένα PDF ακόμη",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            byYear.forEach { (year, entries) ->
                item(key = "year-$year") {
                    Text(
                        "$year  ·  ${entries.size} ${if (entries.size == 1) "προσφορά" else "προσφορές"}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                    )
                }
                items(entries, key = { it.first.offer.id }) { (details, file) ->
                    Card(
                        Modifier.fillMaxWidth().clickable { preview = file },
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.PictureAsPdf,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(
                                    details.offer.address.ifBlank { "(χωρίς διεύθυνση)" },
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                Text(
                                    "${details.offer.dateEpochDay.asOfferDate()} · ${details.total.asMoney()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { sharePdf(context, file) }) {
                                Icon(Icons.Default.Share, contentDescription = "Κοινοποίηση")
                            }
                        }
                    }
                }
            }
        }
    }

    preview?.let { file ->
        PdfPreviewDialog(file) { preview = null }
    }
}

private fun sharePdf(context: android.content.Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "application/pdf"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                "Κοινοποίηση PDF",
            ),
        )
    }.onFailure {
        Toast.makeText(context, "Απέτυχε: ${it.message}", Toast.LENGTH_LONG).show()
    }
}
