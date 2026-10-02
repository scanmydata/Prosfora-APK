package gr.prosfora.app.debt

import gr.prosfora.app.data.db.DebtEntity
import gr.prosfora.app.data.db.DebtKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Το πλάνο δόσεων, που ζει πάνω στην οφειλή ώστε να ξαναρυθμίζεται αργότερα.
 */
class InstallmentPlanTest {

    private fun day(text: String) = LocalDate.parse(text).toEpochDay()

    private fun debt(
        reference: String = "802576637 910102203 634044502034",
        amount: Double = 1200.0,
        description: String = "ΠΡΟΣΩΡ.ΦΟΡΟΥ ΜΙΣΘΩΤΩΝ ΥΠΗΡΕΣ.Ν.4172/2013",
        id: String? = null,
        plan: String = "",
    ) = DebtEntity(
        id = id ?: DebtEntity.idFor(DebtKind.AADE, 2026, 6, reference, ""),
        kind = DebtKind.AADE,
        periodMonth = 6,
        periodYear = 2026,
        dueDay = day("2026-08-31"),
        amount = amount,
        reference = reference,
        description = description,
        installmentPlan = plan,
    )

    @Test
    fun `η αποθηκευμένη μορφή διαβάζεται πίσω`() {
        val plan = InstallmentPlan(1234.56, 102.88, 12, day("2026-08-31"))
        assertEquals(plan, InstallmentPlan.parse(plan.format()))
    }

    @Test
    fun `σκουπίδια στο κελί δεν γίνονται πλάνο`() {
        assertNull(InstallmentPlan.parse(""))
        assertNull(InstallmentPlan.parse("χωρίς|δόσεις"))
        assertNull(InstallmentPlan.parse("100,0|10|0"))
    }

    /** Η στρογγύλευση δεν επιτρέπεται να χάσει λεπτά: πέφτει στην τελευταία. */
    @Test
    fun `το άθροισμα των δόσεων ισούται με το σύνολο`() {
        val plan = InstallmentPlan(total = 100.0, installment = 33.33, count = 3, firstDueDay = day("2026-08-31"))
        assertEquals(listOf(33.33, 33.33, 33.34), plan.amounts())
        assertEquals(100.0, plan.amounts().sum(), 0.001)
    }

    /** Πρώτη δόση όπως δόθηκε· οι επόμενες τελευταία εργάσιμη του μήνα. */
    @Test
    fun `οι επόμενες δόσεις λήγουν τελευταία εργάσιμη`() {
        // 31/12/2026 είναι Πέμπτη και μένει· 31/01/2027 είναι Κυριακή → 29/01
        val plan = InstallmentPlan(300.0, 100.0, 3, day("2026-11-10"))
        assertEquals(
            listOf(day("2026-11-10"), day("2026-12-31"), day("2027-01-29")),
            plan.dueDays(),
        )
    }

    @Test
    fun `μια οφειλή σπάει σε δόσεις με σταθερά id`() {
        val single = debt()
        val plan = InstallmentPlan(1200.0, 400.0, 3, day("2026-08-31"))
        val rows = InstallmentPlan.reshape(listOf(single), plan, asInstallments = true)

        assertEquals(3, rows.size)
        assertEquals(1200.0, rows.sumOf { it.amount }, 0.001)
        assertTrue(rows[0].description.endsWith("· δόση 1/3"))
        assertEquals(1 to 3, InstallmentPlan.doseOf(rows[0]))
        // Το ίδιο πλάνο σε άλλη συσκευή δίνει τα ίδια id — καμία διπλοεγγραφή
        assertEquals(
            rows.map { it.id },
            InstallmentPlan.reshape(listOf(single), plan, asInstallments = true).map { it.id },
        )
        assertEquals(3, rows.map { it.id }.distinct().size)
    }

    @Test
    fun `οι δόσεις ξαναγίνονται μία οφειλή`() {
        val plan = InstallmentPlan(1200.0, 400.0, 3, day("2026-08-31"))
        val doses = InstallmentPlan.reshape(listOf(debt()), plan, asInstallments = true)

        val back = InstallmentPlan.reshape(doses, plan, asInstallments = false)
        assertEquals(1, back.size)
        assertEquals(1200.0, back.single().amount, 0.001)
        assertNull(InstallmentPlan.doseOf(back.single()))
        assertEquals(debt().id, back.single().id)
    }

    /** Μία γραμμή που δεν είναι δόση κρατάει το id της: δεν γίνεται νέα εγγραφή. */
    @Test
    fun `η χειρόγραφη οφειλή κρατάει το id της`() {
        val manual = debt(reference = "", id = "χειρόγραφη-1")
        val rows = InstallmentPlan.reshape(
            listOf(manual),
            InstallmentPlan(500.0, 500.0, 1, null),
            asInstallments = false,
        )
        assertEquals("χειρόγραφη-1", rows.single().id)
    }

    @Test
    fun `το πλάνο προκύπτει από την οφειλή όταν δεν έχει αποθηκευτεί`() {
        val plan = InstallmentPlan.forDebt(debt(amount = 49.75), groupTotal = 49.75)
        assertEquals(1, plan.count)
        assertEquals(49.75, plan.total, 0.001)
    }

    /** Το σύνολο ακολουθεί τα πραγματικά ποσά, αν ο χρήστης τα διόρθωσε. */
    @Test
    fun `το αποθηκευμένο πλάνο παίρνει το τρέχον σύνολο`() {
        val stored = InstallmentPlan(1200.0, 400.0, 3, day("2026-08-31")).format()
        val plan = InstallmentPlan.forDebt(debt(plan = stored), groupTotal = 900.0)
        assertEquals(900.0, plan.total, 0.001)
        assertEquals(3, plan.count)
    }

    @Test
    fun `η ομάδα μαζεύει τις δόσεις της ίδιας οφειλής`() {
        val doses = InstallmentPlan.reshape(
            listOf(debt()),
            InstallmentPlan(1200.0, 400.0, 3, day("2026-08-31")),
            asInstallments = true,
        )
        val other = debt(reference = "άλλη ταυτότητα", amount = 10.0)
        val group = InstallmentPlan.groupOf(doses + other, doses[1])
        assertEquals(3, group.size)
        assertEquals(listOf(1, 2, 3), group.mapNotNull { InstallmentPlan.doseOf(it)?.first })
    }

    /**
     * Χωρίς ταυτότητα οφειλής, η ομάδα δεν σαρώνει ό,τι βρει στον ίδιο μήνα:
     * δύο χειρόγραφες οφειλές του ίδιου μήνα είναι δύο διαφορετικά πράγματα.
     */
    @Test
    fun `χωρίς ταυτότητα δεν μπαίνουν άσχετες οφειλές στην ομάδα`() {
        val first = debt(reference = "", id = "χειρόγραφη-1")
        val second = debt(reference = "", id = "χειρόγραφη-2")
        assertEquals(listOf("χειρόγραφη-1"), InstallmentPlan.groupOf(listOf(first, second), first).map { it.id })
    }
}
