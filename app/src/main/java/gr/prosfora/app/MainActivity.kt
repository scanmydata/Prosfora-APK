package gr.prosfora.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import gr.prosfora.app.google.ConnectLink
import gr.prosfora.app.ui.ConnectInviteDialog
import gr.prosfora.app.ui.ProsforaNavHost
import gr.prosfora.app.ui.ProsforaSplash
import gr.prosfora.app.ui.theme.ProsforaTheme

class MainActivity : ComponentActivity() {

    /**
     * Η πρόσκληση που άνοιξε την εφαρμογή. Ζει εδώ και όχι στο Compose, γιατί
     * μπορεί να φτάσει και σε ήδη ανοιχτή εφαρμογή, μέσω [onNewIntent].
     */
    private val invite = mutableStateOf<ConnectLink.Invite?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        invite.value = ConnectLink.parse(intent?.data)

        setContent {
            ProsforaTheme {
                var splashDone by remember { mutableStateOf(false) }
                if (splashDone) {
                    ProsforaNavHost()
                    invite.value?.let { pending ->
                        ConnectInviteDialog(pending) { invite.value = null }
                    }
                } else {
                    ProsforaSplash(onFinished = { splashDone = true })
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ConnectLink.parse(intent.data)?.let { invite.value = it }
    }
}
