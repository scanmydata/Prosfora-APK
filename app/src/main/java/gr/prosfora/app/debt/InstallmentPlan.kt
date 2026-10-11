package gr.prosfora.app.debt

import gr.prosfora.app.data.db.DebtEntity
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * Το πλάνο δόσεων μιας οφειλής, όπως το διάβασε το έντυπο ή το όρισε ο χρήστης.
 *
 * Ζει **πάνω στην οφειλή**, όχι μόνο μέσα στον διάλογο εισαγωγής. Παλιά το
 * πλάνο υπήρχε μόνο όσο κρατούσε το παράθυρο της εισαγωγής: αν διάλεγες
 * «συνολική οφειλή», η πληροφορία ότι μπορεί να σπάσει σε δόσεις χανόταν για
 * πάντα. Τώρα αποθηκεύεται, άρα η ίδια οφειλή ξαναρυθμίζεται όποτε θέλεις.
 *
 * Μορφή αποθήκευσης — διαβάζεται και μέσα στο φύλλο του Drive:
 * `σύνολο|ποσό δόσης|αριθμός δόσεων|πρώτη λήξη σε epoch day`.
 */
data class InstallmentPlan(
    val total: Double,
    val installment: Double,
    val count: Int,
    val firstDueDay: Long?,
) {
    fun format(): String = listOf(
        money(total).toString(),
        money(installment).toString(),
        count.toString(),
        firstDueDay?.toString().orEmpty(),
    ).joinToString("|")

    /**
     * Πόσες ισόποσες δόσεις **επιτρέπει το έντυπο**.
     *
     * Το ποσό της πρώτης δόσης το λέει, χωρίς να χρειάζεται να γραφτεί πουθενά:
     * όταν είναι το μισό του συνόλου, οι δόσεις είναι δύο. Η ΑΑΔΕ γράφει
     * ισόποσες μηνιαίες δόσεις, οπότε ο λόγος συνόλου προς δόση **είναι** ο
     * αριθμός τους.
     *
     * Όταν τα ποσά δεν βγάζουν καθαρό λόγο —χειρόγραφη οφειλή, OCR που έχασε
     * ψηφίο— μένει ο αριθμός που είχε διαβαστεί, και τουλάχιστον ένας.
     */
    fun allowedCount(): Int {
        if (total <= 0.0 || installment <= 0.0) return count.coerceAtLeast(1)
        val ratio = total / installment
        val rounded = Math.round(ratio).toInt()
        return if (rounded >= 1 && Math.abs(ratio - rounded) <= 0.05) rounded else count.coerceAtLeast(1)
    }

    /** Τα ποσά των δόσεων· η τελευταία κουβαλάει τη στρογγύλευση. */
    fun amounts(): List<Double> = (0 until count).map { index ->
        if (index == count - 1) money(total - money(installment) * (count - 1)) else money(installment)
    }

    /** Οι ημερομηνίες λήξης: η πρώτη όπως δόθηκε, οι επόμενες τελευταία εργάσιμη του μήνα. */
    fun dueDays(): List<Long?> {
        val first = firstDueDay?.let(LocalDate::ofEpochDay) ?: return List(count) { null }
        return (0 until count).map { index ->
            if (index == 0) first.toEpochDay() else lastBusinessDay(first.plusMonths(index.toLong())).toEpochDay()
        }
    }

    companion object {
        private fun money(value: Double): Double = Math.round(value * 100.0) / 100.0

        private val DOSE = Regex("""δόση\s+(\d+)\s*/\s*(\d+)""", RegexOption.IGNORE_CASE)

        fun parse(text: String): InstallmentPlan? {
            val parts = text.split('|')
            if (parts.size < 3) return null
            val total = parts[0].trim().toDoubleOrNull() ?: return null
            val installment = parts[1].trim().toDoubleOrNull() ?: return null
            val count = parts[2].trim().toIntOrNull()?.takeIf { it in 1..240 } ?: return null
            return InstallmentPlan(total, installment, count, parts.getOrNull(3)?.trim()?.toLongOrNull())
        }

        fun of(info: AadeInstallmentParser.Info): InstallmentPlan =
            InstallmentPlan(info.totalAmount, info.installmentAmount, info.installmentCount, info.firstDueDay)

        /** Η αποθηκευμένη ρύθμιση, ή μια αρχική από την ίδια την οφειλή. */
        fun forDebt(debt: DebtEntity, groupTotal: Double): InstallmentPlan =
            parse(debt.installmentPlan)?.let { stored ->
                // Το σύνολο ακολουθεί την πραγματικότητα: ο χρήστης μπορεί να
                // έχει διορθώσει ποσά με το χέρι μετά την εισαγωγή.
                if (groupTotal > 0.0) stored.copy(total = money(groupTotal)) else stored
            } ?: InstallmentPlan(
                total = money(groupTotal.takeIf { it > 0.0 } ?: debt.amount),
                installment = money(groupTotal.takeIf { it > 0.0 } ?: debt.amount),
                count = 1,
                firstDueDay = debt.dueDay,
            )

        /** «… · δόση 3/12» → 3 προς 12. */
        fun doseOf(debt: DebtEntity): Pair<Int, Int>? = DOSE.find(debt.description)?.let { match ->
            val index = match.groupValues[1].toIntOrNull() ?: return null
            val of = match.groupValues[2].toIntOrNull() ?: return null
            index to of
        }

        /**
         * Όλες οι γραμμές που ανήκουν στην ίδια οφειλή: η συνολική και οι δόσεις.
         *
         * Κλειδί η ταυτότητα της οφειλής — φορέας, ταυτότητα/RF, πρόσωπο.
         * **Όχι** η περίοδος: οι δόσεις ζουν σε διαφορετικούς μήνες, γιατί κάθε
         * μία ανήκει στον μήνα που πληρώνεται.
         *
         * Όταν η ταυτότητα λείπει, μπαίνουν μόνο η ίδια η οφειλή και γραμμές
         * που δηλώνουν δόση, ώστε να μη σαρωθούν άσχετες εγγραφές.
         */
        fun groupOf(all: List<DebtEntity>, debt: DebtEntity): List<DebtEntity> = all
            .filter { row ->
                !row.deleted &&
                    row.kind == debt.kind &&
                    row.reference == debt.reference &&
                    row.personName == debt.personName &&
                    (debt.reference.isNotBlank() || row.id == debt.id || doseOf(row) != null)
            }
            .sortedBy { doseOf(it)?.first ?: 0 }

        /**
         * Οι γραμμές που πρέπει να υπάρχουν για αυτό το πλάνο.
         *
         * Τα id βγαίνουν από την ταυτότητα της οφειλής, όχι από τη σειρά: δύο
         * συσκευές που σπάνε την ίδια οφειλή στις ίδιες δόσεις παράγουν τα ίδια
         * id, οπότε η κοινόχρηστη βάση δεν αποκτά δίδυμες εγγραφές.
         */
        fun reshape(
            group: List<DebtEntity>,
            plan: InstallmentPlan,
            asInstallments: Boolean,
        ): List<DebtEntity> {
            val base = group.firstOrNull { doseOf(it) == null } ?: group.firstOrNull() ?: return emptyList()

            // Η περίοδος του κλειδιού είναι ο μήνας της πρώτης δόσης, όχι του
            // `base`: η κάθε δόση ζει στον δικό της μήνα, οπότε αν το κλειδί
            // έπαιρνε τον μήνα της γραμμής, το μάζεμα των δόσεων πίσω σε μία
            // θα έφτιαχνε οφειλή με άλλο id από την αρχική.
            val keyDate = plan.firstDueDay?.let(LocalDate::ofEpochDay)
            val keyYear = keyDate?.year ?: base.periodYear
            val keyMonth = keyDate?.monthValue ?: base.periodMonth
            fun idWith(suffix: String) = DebtEntity.idFor(
                base.kind,
                keyYear,
                keyMonth,
                base.reference + suffix,
                base.personName,
            )
            // Μια χειρόγραφη οφειλή έχει δικό της τυχαίο id· μένει όπως είναι
            val plainDescription = base.description.replace(DOSE, "").trimEnd(' ', '·', '-').ifBlank { "Βεβαιωμένη οφειλή" }

            if (!asInstallments || plan.count <= 1) {
                val totalId = if (group.size == 1 && doseOf(base) == null) base.id else idWith("")
                return listOf(
                    base.copy(
                        id = totalId,
                        amount = money(plan.total),
                        dueDay = plan.firstDueDay ?: base.dueDay,
                        description = plainDescription,
                    ),
                )
            }

            val amounts = plan.amounts()
            val dues = plan.dueDays()
            return (0 until plan.count).map { index ->
                val due = dues[index] ?: base.dueDay
                // Κάθε δόση ανήκει στον μήνα που πληρώνεται. Η οφειλή του
                // Σεπτεμβρίου σε δύο δόσεις δίνει μία στον Σεπτέμβριο και μία
                // στον Οκτώβριο — αλλιώς και οι δύο στοιβάζονταν στον
                // Σεπτέμβριο, με σωστή ημερομηνία λήξης αλλά σε λάθος μήνα.
                val month = due?.let(LocalDate::ofEpochDay)
                base.copy(
                    id = idWith("|dose:${index + 1}/${plan.count}"),
                    amount = amounts[index],
                    dueDay = due,
                    periodMonth = month?.monthValue ?: base.periodMonth,
                    periodYear = month?.year ?: base.periodYear,
                    description = "$plainDescription · δόση ${index + 1}/${plan.count}",
                )
            }
        }

        fun lastBusinessDay(date: LocalDate): LocalDate = lastBusinessDay(date.year, date.monthValue)

        fun lastBusinessDay(year: Int, month: Int): LocalDate {
            var day = YearMonth.of(year, month).atEndOfMonth()
            while (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) {
                day = day.minusDays(1)
            }
            return day
        }
    }
}
