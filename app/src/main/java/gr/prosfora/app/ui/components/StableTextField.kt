package gr.prosfora.app.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay

/**
 * Πεδίο κειμένου που **δεν πετάει τον κέρσορα στην αρχή**.
 *
 * Το πρόβλημα: αν το `value` έρχεται από τη βάση, κάθε πλήκτρο ξεκινάει έναν
 * κύκλο «γράψε στη Room → Flow → recomposition». Μέχρι να γυρίσει η νέα τιμή,
 * το Compose ξαναζωγραφίζει το πεδίο με την **παλιά** συμβολοσειρά· η θέση του
 * κέρσορα μηδενίζεται και το επόμενο γράμμα προσαρτάται στο τέλος της παλιάς
 * τιμής. Έτσι «γύριζε ο pointer στην αρχή».
 *
 * Η λύση: το πεδίο κρατάει δικό του [TextFieldValue] με τη θέση του κέρσορα και
 * είναι η πηγή αλήθειας όσο πληκτρολογεί ο χρήστης. Η εγγραφή στη βάση γίνεται
 * με καθυστέρηση, και η εξωτερική τιμή ξαναδιαβάζεται μόνο όταν αλλάξει από
 * αλλού (π.χ. συγχρονισμός) και διαφέρει από αυτό που βλέπει ο χρήστης.
 */
@Composable
fun StableTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    debounceMillis: Long = 350L,
) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    val latestOnChange by rememberUpdatedState(onValueChange)

    // Εξωτερική αλλαγή (συγχρονισμός, επαναφορά): υιοθετείται μόνο αν είναι
    // πράγματι διαφορετική, αλλιώς θα πατούσε πάνω σε ό,τι πληκτρολογείται.
    LaunchedEffect(value) {
        if (value != field.text) {
            field = TextFieldValue(value, TextRange(value.length))
        }
    }

    LaunchedEffect(field.text) {
        if (field.text != value) {
            delay(debounceMillis)
            latestOnChange(field.text)
        }
    }

    OutlinedTextField(
        value = field,
        onValueChange = { field = it },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        modifier = modifier,
    )
}
