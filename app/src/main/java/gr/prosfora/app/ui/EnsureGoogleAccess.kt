package gr.prosfora.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.google.rememberGoogleAuthorizer

/**
 * Ζητά την έγκριση της Google μόλις ανοίξει η εφαρμογή, αν δεν έχει δοθεί ήδη.
 *
 * Πριν, η έγκριση ζητιόταν την πρώτη φορά που χρειαζόταν — συνήθως πατώντας
 * «Κοινόχρηστη βάση» βαθιά μέσα στις ρυθμίσεις. Ήταν σαν να χαλάει κάτι: όλα
 * φαίνονταν έτοιμα ώσπου ξαφνικά εμφανιζόταν παράθυρο της Google.
 *
 * Μία φορά μόνο: μόλις πετύχει, το [GoogleSettings.googleConnected] μένει
 * σημειωμένο και δεν ξαναρωτάει σε κάθε άνοιγμα. Αν ο χρήστης το ακυρώσει,
 * θα ξαναζητηθεί την επόμενη φορά — η εφαρμογή χωρίς αυτό δεν κάνει τίποτα.
 */
@Composable
fun EnsureGoogleAccess() {
    val context = LocalContext.current
    val settings = remember { GoogleSettings(context) }
    val authorizer = rememberGoogleAuthorizer()

    LaunchedEffect(Unit) {
        if (settings.googleConnected) return@LaunchedEffect
        // Σιωπηλή αποτυχία: αν ο χρήστης πει όχι, συνεχίζει offline και θα
        // ξαναρωτηθεί όταν πράγματι χρειαστεί κάτι από τη Google
        runCatching { authorizer.accessToken() }
    }
}
