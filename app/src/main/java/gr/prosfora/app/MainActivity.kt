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
import gr.prosfora.app.notify.DriveNotifier
import gr.prosfora.app.ui.ConnectInviteDialog
import gr.prosfora.app.ui.ProsforaNavHost
import gr.prosfora.app.ui.ProsforaSplash
import gr.prosfora.app.ui.theme.ProsforaTheme

class MainActivity : ComponentActivity() {
    private val invite = mutableStateOf<ConnectLink.Invite?>(null)
    private val pendingDebtId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)

        setContent {
            ProsforaTheme {
                var splashDone by remember { mutableStateOf(false) }
                if (splashDone) {
                    ProsforaNavHost(
                        openDebtId = pendingDebtId.value,
                        onDebtNavigationConsumed = { pendingDebtId.value = null },
                    )
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
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        invite.value = ConnectLink.parse(intent?.data)
        pendingDebtId.value = intent?.getStringExtra(DriveNotifier.EXTRA_OPEN_DEBT_ID)
            ?.takeIf { it.isNotBlank() }
    }
}
