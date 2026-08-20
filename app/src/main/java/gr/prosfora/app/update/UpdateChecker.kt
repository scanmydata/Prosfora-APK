package gr.prosfora.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Έλεγχος και εγκατάσταση ενημερώσεων από GitHub Releases.
 * Κάθε push στο main παράγει release με tag `v0.1.<run_number>` — βλ. .github/workflows/release.yml
 */
object UpdateChecker {

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/scanmydata/Prosfora-APK/releases/latest"

    private val client = OkHttpClient()

    data class Release(val tag: String, val name: String, val notes: String, val apkUrl: String)

    /** Επιστρέφει το release μόνο αν είναι νεότερο από την τρέχουσα έκδοση, αλλιώς null. */
    suspend fun checkForUpdate(currentVersionName: String): Release? = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(LATEST_RELEASE_URL)
            .header("Accept", "application/vnd.github+json")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext null
            val body = response.body?.string() ?: return@withContext null
            val json = JSONObject(body)

            val tag = json.optString("tag_name").ifBlank { return@withContext null }
            val assets = json.optJSONArray("assets") ?: return@withContext null
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.optString("name").endsWith(".apk", ignoreCase = true)) {
                    apkUrl = asset.optString("browser_download_url")
                    break
                }
            }
            if (apkUrl.isNullOrBlank()) return@withContext null

            val remote = tag.removePrefix("v")
            if (compareVersions(remote, currentVersionName) <= 0) return@withContext null

            Release(
                tag = tag,
                name = json.optString("name", tag),
                notes = json.optString("body", ""),
                apkUrl = apkUrl,
            )
        }
    }

    /** Κατεβάζει το APK στην cache του app και επιστρέφει το αρχείο. */
    suspend fun download(context: Context, release: Release): File = withContext(Dispatchers.IO) {
        val target = File(context.cacheDir, "update-${release.tag}.apk")
        val request = Request.Builder().url(release.apkUrl).build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Αποτυχία λήψης: HTTP ${response.code}" }
            val stream = response.body?.byteStream() ?: error("Κενή απόκριση")
            target.outputStream().use { out -> stream.copyTo(out) }
        }
        target
    }

    /** Ανοίγει τον installer του Android. Ο χρήστης επιβεβαιώνει την εγκατάσταση. */
    fun install(context: Context, apk: File) {
        val uri: Uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apk,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /** Σύγκριση τύπου 0.1.12 vs 0.1.9 — αριθμητικά ανά τμήμα, όχι αλφαβητικά. */
    internal fun compareVersions(a: String, b: String): Int {
        val left = a.split(".").map { it.toIntOrNull() ?: 0 }
        val right = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return l.compareTo(r)
        }
        return 0
    }
}
