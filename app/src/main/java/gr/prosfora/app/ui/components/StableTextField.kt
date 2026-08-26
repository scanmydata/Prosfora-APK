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
import androidx.compose.ui.focus.onFocusChanged
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
 *
 * Όσο είναι επιλεγμένο, το πεδίο **ανοίγει σε πολλές γραμμές** ώστε να φαίνεται
 * όλο το περιεχόμενο. Σε μία γραμμή, ένα μακρύ κείμενο κρύβεται δεξιά και η
 * επιλογή με το δάχτυλο δεν το κυλάει αξιόπιστα· έτσι δεν υπάρχει τίποτα
 * κρυμμένο τη στιγμή που το διορθώνεις.
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
    /** Πόσες γραμμές ανοίγει όσο γράφεις. 1 = μένει όπως ήταν. */
    expandedLines: Int = 4,
) {
    var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
    var focused by remember { mutableStateOf(false) }
    val latestOnChange by rememberUpdatedState(onValueChange)

    val open = focused && singleLine && expandedLines > 1

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
        singleLine = singleLine && !open,
        minLines = minLines,
        // Το Compose απαιτεί maxLines == 1 όσο το πεδίο είναι σε μία γραμμή
        maxLines = when {
            open -> expandedLines
            singleLine -> 1
            else -> Int.MAX_VALUE
        },
        keyboardOptions = keyboardOptions,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
    )
}
