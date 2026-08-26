package gr.prosfora.app.ui.settings

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import gr.prosfora.app.google.GoogleSettings
import gr.prosfora.app.message.GreetingStyle
import gr.prosfora.app.message.MessageField
import gr.prosfora.app.message.MessageTemplates

private enum class TemplateTab(val label: String) {
    EMAIL("Email"),
    SMS("SMS"),
    VIBER("Viber"),
}

/**
 * Επεξεργασία των προτύπων για email, SMS και Viber, με κουμπιά που εισάγουν
 * δυναμικά πεδία στη θέση του κέρσορα.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageTemplatesSettings(googleSettings: GoogleSettings) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(TemplateTab.EMAIL) }

    var subject by remember {
        mutableStateOf(TextFieldValue(googleSettings.emailSubjectTemplate))
    }
    var emailBody by remember { mutableStateOf(TextFieldValue(googleSettings.emailBodyTemplate)) }
    var sms by remember { mutableStateOf(TextFieldValue(googleSettings.smsTemplate)) }
    var viber by remember { mutableStateOf(TextFieldValue(googleSettings.viberTemplate)) }

    /** Εισαγωγή στη θέση του κέρσορα, όχι στο τέλος. */
    fun insert(current: TextFieldValue, token: String): TextFieldValue {
        val at = current.selection.start.coerceIn(0, current.text.length)
        val text = current.text.substring(0, at) + token + current.text.substring(at)
        return TextFieldValue(text, TextRange(at + token.length))
    }


    GreetingChooser(googleSettings)
    HorizontalDivider()

    TabRow(selectedTabIndex = tab.ordinal) {
        TemplateTab.entries.forEach { entry ->
            Tab(
                selected = tab == entry,
                onClick = { tab = entry },
                text = { Text(entry.label, maxLines = 1) },
            )
        }
    }

    if (tab == TemplateTab.EMAIL) {
        OutlinedTextField(
            value = subject,
            onValueChange = { subject = it },
            label = { Text("Θέμα") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    val body = when (tab) {
        TemplateTab.EMAIL -> emailBody
        TemplateTab.SMS -> sms
        TemplateTab.VIBER -> viber
    }
    OutlinedTextField(
        value = body,
        onValueChange = {
            when (tab) {
                TemplateTab.EMAIL -> emailBody = it
                TemplateTab.SMS -> sms = it
                TemplateTab.VIBER -> viber = it
            }
        },
        label = { Text("Κείμενο") },
        minLines = 5,
        modifier = Modifier.fillMaxWidth(),
    )

    Text(
        "Δυναμικά πεδία — πάτα για εισαγωγή στη θέση του κέρσορα",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MessageField.entries.forEach { field ->
            AssistChip(
                onClick = {
                    when (tab) {
                        TemplateTab.EMAIL -> emailBody = insert(emailBody, field.token)
                        TemplateTab.SMS -> sms = insert(sms, field.token)
                        TemplateTab.VIBER -> viber = insert(viber, field.token)
                    }
                },
                label = { Text(field.label, maxLines = 1) },
            )
        }
    }
    if (tab == TemplateTab.EMAIL) {
        TextButton(onClick = { subject = insert(subject, MessageField.ADDRESS.token) }) {
            Text("+ {διεύθυνση} στο θέμα")
        }
    }

    HorizontalDivider()

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Παράδειγμα", style = MaterialTheme.typography.labelLarge)
            Text(
                MessageTemplates.render(
                            body.text,
                            SampleOffer.value,
                            greeting = googleSettings.greetingOptions,
                        ),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    Button(
        onClick = {
            googleSettings.emailSubjectTemplate = subject.text
            googleSettings.emailBodyTemplate = emailBody.text
            googleSettings.smsTemplate = sms.text
            googleSettings.viberTemplate = viber.text
            Toast.makeText(context, "Τα πρότυπα αποθηκεύτηκαν", Toast.LENGTH_SHORT).show()
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Αποθήκευση προτύπων") }

    TextButton(
        onClick = {
            subject = TextFieldValue(MessageTemplates.DEFAULT_EMAIL_SUBJECT)
            emailBody = TextFieldValue(MessageTemplates.DEFAULT_EMAIL_BODY)
            sms = TextFieldValue(MessageTemplates.DEFAULT_SMS)
            viber = TextFieldValue(MessageTemplates.DEFAULT_VIBER)
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text("Επαναφορά προεπιλογών") }
}

/**
 * Πώς προσφωνείται ο πελάτης στο `{χαιρετισμός}`.
 *
 * Δεν είναι το ίδιο μήνυμα σε όλους: σε πολυκατοικία γράφεις στον διαχειριστή
 * με το επώνυμο, σε γνωστό πελάτη με το μικρό όνομα.
 */
@Composable
private fun GreetingChooser(googleSettings: GoogleSettings) {
    var options by remember { mutableStateOf(googleSettings.greetingOptions) }

    Text("Προσφώνηση", style = MaterialTheme.typography.labelLarge)
    GreetingStyle.entries.forEach { style ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    options = options.copy(style = style)
                    googleSettings.greetingOptions = options
                },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = options.style == style,
                onClick = {
                    options = options.copy(style = style)
                    googleSettings.greetingOptions = options
                },
            )
            Column(Modifier.padding(start = 4.dp)) {
                Text(style.label, style = MaterialTheme.typography.bodyMedium)
                Text(
                    style.example,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (options.style == GreetingStyle.LAST_NAME) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = options.useTitle,
                onCheckedChange = {
                    options = options.copy(useTitle = it)
                    googleSettings.greetingOptions = options
                },
            )
            Text(
                "Με «κύριε» / «κυρία»",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
        Text(
            "Χρειάζεται να έχεις δηλώσει φύλο στην προσφορά. Στα ανδρικά επώνυμα " +
                "μπαίνει και η κλητική: Παπαδόπουλος γίνεται Παπαδόπουλε.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
