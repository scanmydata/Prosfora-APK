package gr.prosfora.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import gr.prosfora.app.ui.OffersScreen
import gr.prosfora.app.ui.theme.ProsforaTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ProsforaTheme {
                OffersScreen()
            }
        }
    }
}
