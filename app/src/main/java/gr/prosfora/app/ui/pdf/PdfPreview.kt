package gr.prosfora.app.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val MIN_ZOOM = 1f
private const val MAX_ZOOM = 4f
private const val ZOOM_STEP = 1.4f

/**
 * Προεπισκόπηση PDF με τον renderer του Android (PdfRenderer) — καμία εξωτερική
 * βιβλιοθήκη. Κάθε σελίδα αποδίδεται στις πραγματικές της αναλογίες, οπότε μια
 * σελίδα A4 φαίνεται ως A4.
 *
 * Μεγέθυνση: κουμπιά +/− και διπλό πάτημα. Δεν χρησιμοποιείται pinch, γιατί θα
 * έτρωγε τις κινήσεις κύλισης· τα κουμπιά δουλεύουν και με το ένα χέρι.
 */
@Composable
fun PdfPreview(
    file: File,
    modifier: Modifier = Modifier,
) {
    var zoom by remember(file.path) { mutableFloatStateOf(MIN_ZOOM) }
    // Πάνω από 2x η απόδοση των 1240px φαίνεται θολή, οπότε ξαναγράφεται πιο ψηλά
    val renderWidth = if (zoom > 2f) 2200 else 1240

    var pages by remember(file.path, file.lastModified()) {
        mutableStateOf<List<Bitmap>?>(null)
    }
    var error by remember(file.path) { mutableStateOf<String?>(null) }

    LaunchedEffect(file.path, file.lastModified(), renderWidth) {
        runCatching { renderPages(file, renderWidth) }
            // Σε φτωχή μνήμη η απόδοση υψηλής ανάλυσης μπορεί να μη χωρέσει·
            // τότε μένουμε στη βασική αντί να αδειάσει η οθόνη
            .recoverCatching { if (renderWidth > 1240) renderPages(file, 1240) else throw it }
            .onSuccess { pages = it }
            .onFailure { error = it.message ?: "Αδύνατη η προεπισκόπηση" }
    }

    val rendered = pages
    when {
        error != null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(error!!, style = MaterialTheme.typography.bodyMedium)
        }

        rendered == null -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }

        else -> BoxWithConstraints(modifier.fillMaxSize()) {
            val pageWidth = (maxWidth - 32.dp) * zoom

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = { zoom = if (zoom > MIN_ZOOM) MIN_ZOOM else 2f },
                        )
                    }
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                rendered.forEachIndexed { index, page ->
                    Image(
                        bitmap = page.asImageBitmap(),
                        contentDescription = "Σελίδα ${index + 1}",
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier
                            .width(pageWidth)
                            .background(androidx.compose.ui.graphics.Color.White),
                    )
                }
            }

            ZoomControls(
                zoom = zoom,
                onZoom = { zoom = it.coerceIn(MIN_ZOOM, MAX_ZOOM) },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }
}

@Composable
private fun ZoomControls(zoom: Float, onZoom: (Float) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 6.dp,
        shadowElevation = 4.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = zoom > MIN_ZOOM, onClick = { onZoom(zoom / ZOOM_STEP) }) {
                Icon(Icons.Default.ZoomOut, contentDescription = "Σμίκρυνση")
            }
            Text(
                "${(zoom * 100).toInt()}%",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(enabled = zoom < MAX_ZOOM, onClick = { onZoom(zoom * ZOOM_STEP) }) {
                Icon(Icons.Default.ZoomIn, contentDescription = "Μεγέθυνση")
            }
            IconButton(enabled = zoom != MIN_ZOOM, onClick = { onZoom(MIN_ZOOM) }) {
                Icon(Icons.Default.FitScreen, contentDescription = "Προσαρμογή στην οθόνη")
            }
        }
    }
}

private suspend fun renderPages(file: File, widthPx: Int): List<Bitmap> =
    withContext(Dispatchers.IO) {
        require(file.exists() && file.length() > 0) { "Το PDF δεν βρέθηκε" }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                (0 until renderer.pageCount).map { index ->
                    renderer.openPage(index).use { page ->
                        val scale = widthPx.toFloat() / page.width
                        val bitmap = Bitmap.createBitmap(
                            widthPx,
                            (page.height * scale).toInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888,
                        )
                        // Ο PdfRenderer δεν ζωγραφίζει φόντο· χωρίς αυτό βγαίνει διάφανο
                        bitmap.eraseColor(Color.WHITE)
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmap
                    }
                }
            }
        }
    }

/** Η ίδια προεπισκόπηση σε πλήρη οθόνη, ως διάλογος. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfPreviewDialog(file: File, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Προεπισκόπηση PDF") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Κλείσιμο",
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                PdfPreview(file)
            }
        }
    }
}
