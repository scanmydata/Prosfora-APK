package gr.prosfora.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import gr.prosfora.app.ui.ProsforaNavHost
import gr.prosfora.app.ui.ProsforaSplash
import gr.prosfora.app.ui.theme.ProsforaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProsforaTheme {
                var splashDone by remember { mutableStateOf(false) }
                if (splashDone) {
                    ProsforaNavHost()
                } else {
                    ProsforaSplash(onFinished = { splashDone = true })
                }
            }
        }
    }
}
