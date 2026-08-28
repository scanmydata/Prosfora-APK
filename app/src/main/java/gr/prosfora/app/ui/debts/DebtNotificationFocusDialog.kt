package gr.prosfora.app.ui.debts

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.debt.DebtRepository
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.asOfferDate

@Composable
fun DebtNotificationFocusDialog(
    debtId: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { DebtRepository(context) }
    val debts by remember(repository) { repository.observeAll() }.collectAsState(initial = emptyList())
    val debt: DebtEntity? = debts.firstOrNull { it.id == debtId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Νέα οφειλή") },
        text = {
            if (debt == null) {
                Text("Η οφειλή που προκάλεσε την ειδοποίηση δεν βρίσκεται πλέον στη συσκευή.")
            } else {
                Text(
                    buildString {
                        append(debt.title)
                        append("\n\nΠοσό: ${debt.amount.asMoney()}")
                        append("\nΛήξη: ${debt.dueDay.asOfferDate()}")
                        if (debt.reference.isNotBlank()) append("\nΚωδικός: ${debt.reference}")
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Εντάξει") } },
    )
}
