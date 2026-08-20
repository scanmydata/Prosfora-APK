package gr.prosfora.app.ui.pdf

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Προεπισκόπηση PDF με τον renderer του Android (PdfRenderer) — καμία εξωτερική
 * βιβλιοθήκη. Κάθε σελίδα αποδίδεται στις πραγματικές της αναλογίες, οπότε μια
 * σελίδα A4 φαίνεται ως A4.
 */
@Composable
fun PdfPreview(
    file: File,
    modifier: Modifier = Modifier,
    widthPx: Int = 1240, // ~150 dpi για A4 — αρκετό για ανάγνωση χωρίς να τρώει μνήμη
) {
    var pages by remember(file.path, file.lastModified()) {
        mutableStateOf<List<Bitmap>?>(null)
    }
    var error by remember(file.path) { mutableStateOf<String?>(null) }

    LaunchedEffect(file.path, file.lastModified()) {
        runCatching { renderPages(file, widthPx) }
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

        else -> LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            itemsIndexed(rendered) { index, page ->
                Image(
                    bitmap = page.asImageBitmap(),
                    contentDescription = "Σελίδα ${index + 1}",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(androidx.compose.ui.graphics.Color.White),
                )
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
