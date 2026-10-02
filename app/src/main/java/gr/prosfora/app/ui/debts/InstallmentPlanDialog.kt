package gr.prosfora.app.ui.debts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.debt.InstallmentPlan
import gr.prosfora.app.ui.components.StableTextField
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.asOfferDate
import gr.prosfora.app.util.parseDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val BrandGreen = Color(0xFF00E2A2)

/**
 * Ρύθμιση δόσεων για μια οφειλή που υπάρχει ήδη στη βάση.
 *
 * Το έντυπο της ΑΑΔΕ λέει συχνά και συνολικό ποσό και πλάνο δόσεων. Η οφειλή
 * μπαίνει ως μία συνολική γραμμή, γιατί αυτό θέλει κανείς τις περισσότερες
 * φορές — αλλά η απόφαση δεν είναι οριστική: από εδώ σπάει σε δόσεις ή
 * ξαναμαζεύεται σε μία, όσες φορές χρειαστεί.
 *
 * Ό,τι έχει ήδη πληρωθεί και παραμένει, κρατάει την πληρωμή του.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallmentPlanDialog(
    group: List<DebtEntity>,
    onDismiss: () -> Unit,
    onApply: (InstallmentPlan, Boolean) -> Unit,
) {
    val representative = group.firstOrNull() ?: return
    val currentTotal = group.sumOf { it.amount }
    val stored = remember(group) { InstallmentPlan.forDebt(representative, currentTotal) }

    var asInstallments by remember(group) { mutableStateOf(group.size > 1 || stored.count > 1) }
    var count by remember(group) { mutableStateOf(maxOf(group.size, stored.count).coerceAtLeast(1)) }
    var total by remember(group) { mutableStateOf(stored.total.asMoney().removeSuffix(" €").trim()) }
    var firstDue by remember(group) {
        mutableStateOf(stored.firstDueDay ?: representative.dueDay ?: LocalDate.now().toEpochDay())
    }
    var picking by remember { mutableStateOf(false) }

    val totalValue = total.parseDecimal() ?: stored.total
    val plan = InstallmentPlan(
        total = totalValue,
        installment = if (count > 0) totalValue / count else totalValue,
        count = if (asInstallments) count else 1,
        firstDueDay = firstDue,
    )
    val preview = remember(plan, asInstallments) { InstallmentPlan.reshape(group, plan, asInstallments) }
    val paidCount = group.count { it.paid }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Δόσεις") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    representative.description.ifBlank { representative.kind.label },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = !asInstallments,
                        onClick = { asInstallments = false },
                        label = { Text("Μία οφειλή") },
                    )
                    FilterChip(
                        selected = asInstallments,
                        onClick = { asInstallments = true },
                        label = { Text("Σε δόσεις") },
                    )
                }

                StableTextField(
                    value = total,
                    onValueChange = { total = it },
                    label = "Συνολικό ποσό",
                    modifier = Modifier.fillMaxWidth(),
                )

                if (asInstallments) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Δόσεις", Modifier.weight(1f))
                        IconButton(enabled = count > 1, onClick = { count-- }) {
                            Icon(Icons.Default.Remove, contentDescription = "Λιγότερες")
                        }
                        Text(
                            count.toString(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        IconButton(enabled = count < 120, onClick = { count++ }) {
                            Icon(Icons.Default.Add, contentDescription = "Περισσότερες")
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (asInstallments) "Πρώτη δόση ${firstDue.asOfferDate()}" else "Λήξη ${firstDue.asOfferDate()}",
                        Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { picking = true }) { Text("Αλλαγή") }
                }

                if (asInstallments) {
                    Text(
                        "Η πρώτη δόση λήγει την ημερομηνία που θα διαλέξεις· οι επόμενες " +
                            "την τελευταία εργάσιμη κάθε μήνα.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                HorizontalDivider()
                Text(
                    if (preview.size == 1) "Θα μείνει μία οφειλή" else "Θα γίνουν ${preview.size} γραμμές",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                preview.take(13).forEach { row ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.dueDay?.asOfferDate() ?: "—",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(row.amount.asMoney(), style = MaterialTheme.typography.bodyMedium)
                    }
                }
                if (preview.size > 13) {
                    Text(
                        "…και άλλες ${preview.size - 13}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (paidCount > 0) {
                    Text(
                        "$paidCount ${if (paidCount == 1) "γραμμή είναι πληρωμένη" else "γραμμές είναι πληρωμένες"} " +
                            "— όσες παραμείνουν κρατούν την πληρωμή τους.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = preview.isNotEmpty() && totalValue > 0.0,
                onClick = { onApply(plan, asInstallments) },
            ) { Text("Αποθήκευση", color = BrandGreen) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Άκυρο") } },
    )

    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = firstDue * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    // Ο επιλογέας δουλεύει σε UTC· η μετατροπή γίνεται εκεί,
                    // αλλιώς η τοπική ζώνη μετακινεί τη μέρα κατά μία
                    state.selectedDateMillis?.let { millis ->
                        firstDue = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
                    }
                    picking = false
                }) { Text("Επιλογή", color = BrandGreen) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Άκυρο") } },
        ) { DatePicker(state = state) }
    }
}
