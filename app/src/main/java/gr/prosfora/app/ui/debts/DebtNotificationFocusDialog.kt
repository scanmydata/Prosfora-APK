package gr.prosfora.app.ui.debts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.debt.DebtRepository
import gr.prosfora.app.notify.PendingDebtNotificationStore
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.asOfferDate
import kotlinx.coroutines.launch

@Composable
fun DebtNotificationFocusDialog(
    debtId: String?,
    pendingInstallments: Boolean = false,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { DebtRepository(context) }
    val debts by remember(repository) { repository.observeAll() }.collectAsState(initial = emptyList())
    val debt: DebtEntity? = debtId?.let { id -> debts.firstOrNull { it.id == id } }
    val scope = rememberCoroutineScope()
    var pendingFound by remember(pendingInstallments) {
        mutableStateOf(if (pendingInstallments) PendingDebtNotificationStore.peek(context) else null)
    }
    var installmentsSelected by remember(pendingFound?.driveFileId) { mutableStateOf(false) }

    if (pendingInstallments) {
        val found = pendingFound ?: return
        val plan = found.installmentPlan ?: return

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Οφειλή σε δόσεις") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Το «${found.fileName}» περιέχει πλάνο δόσεων. Επίλεξε πώς θέλεις να αποθηκευτεί.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        "Συνολικό ποσό: ${plan.totalAmount.asMoney()}\n" +
                            "Ποσό δόσης: ${plan.installmentAmount.asMoney()}\n" +
                            "Δόσεις: ${plan.installmentCount}\n" +
                            "Πρώτη λήξη: ${plan.firstDueDay.asOfferDate()}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        FilterChip(
                            selected = !installmentsSelected,
                            onClick = { installmentsSelected = false },
                            label = { Text("Μία οφειλή") },
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = installmentsSelected,
                            onClick = { installmentsSelected = true },
                            label = { Text("${plan.installmentCount} δόσεις") },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val mode = if (installmentsSelected) "INSTALLMENTS" else "TOTAL"
                    val materialized = found.debts.flatMap { debt ->
                        materializeInstallmentDebt(debt, plan, mode)
                    }
                    scope.launch {
                        repository.saveAll(materialized)
                        PendingDebtNotificationStore.consumeFirst(context)
                        val next = PendingDebtNotificationStore.peek(context)
                        pendingFound = next
                        if (next == null) onDismiss()
                    }
                }) {
                    Text("Αποθήκευση")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("Αργότερα") }
            },
        )
        return
    }

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
                        append("\nΛήξη: ${debt.dueDay?.asOfferDate() ?: "—"}")
                        if (debt.reference.isNotBlank()) append("\nΚωδικός: ${debt.reference}")
                    },
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Εντάξει") } },
    )
}
