package gr.prosfora.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import gr.prosfora.app.R
import kotlinx.coroutines.delay

private const val VISIBLE_MILLIS = 1400L

/**
 * Οθόνη εκκίνησης με το επίσημο γραφικό του tovapsimo.gr.
 *
 * Έρχεται αμέσως μετά την splash του συστήματος (που δείχνει μόνο το τρίγωνο σε
 * λευκό φόντο), οπότε η μετάβαση δεν «κόβει».
 */
@Composable
fun ProsforaSplash(onFinished: () -> Unit) {
    var fading by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(
        targetValue = if (fading) 0f else 1f,
        animationSpec = tween(durationMillis = 350),
        label = "splash-fade",
    )

    LaunchedEffect(Unit) {
        delay(VISIBLE_MILLIS)
        fading = true
        delay(350)
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .alpha(alpha),
    ) {
        Image(
            painter = painterResource(R.drawable.splash_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
