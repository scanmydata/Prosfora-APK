package gr.prosfora.app.debt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/**
 * Πόσες δόσεις επιτρέπει το έντυπο.
 *
 * Το σημείωμα της ΑΑΔΕ δεν γράφει πουθενά «2 δόσεις». Το λέει με τα ποσά: όταν
 * η πρώτη δόση είναι το μισό του συνόλου, οι δόσεις είναι δύο. Οι δόσεις είναι
 * ισόποσες μηνιαίες, οπότε ο λόγος συνόλου προς δόση **είναι** ο αριθμός τους.
 *
 * Το κείμενο είναι αυτούσιο από το «ΦΠΑ ΑΥΓΟΥΣΤΟΥ 2026.pdf».
 */
class AadeInstallmentParserTest {

    @Test
    fun `το ΦΠΑ Αυγούστου επιτρέπει δύο δόσεις`() {
        val plan = AadeInstallmentParser.parse(VAT_AUGUST)
            ?: error("δεν αναγνωρίστηκε πλάνο δόσεων")

        assertEquals(3543.17, plan.totalAmount, 0.005)
        assertEquals(1771.59, plan.installmentAmount, 0.005)
        assertEquals(2, plan.installmentCount)
        assertEquals(LocalDate.parse("2026-09-30").toEpochDay(), plan.firstDueDay)
        assertEquals(2, InstallmentPlan.of(plan).allowedCount())
    }

    /** Ένα σημείωμα με εφάπαξ ποσό δεν είναι πλάνο δόσεων. */
    @Test
    fun `εφάπαξ ποσό δεν γίνεται πλάνο δόσεων`() {
        val single = VAT_AUGUST
            .replace("Ποσό δόσης δήλωσης της 30/09/2026 1.771,59 €", "Ποσό δόσης δήλωσης της 30/09/2026 3.543,17 €")
        assertNull(AadeInstallmentParser.parse(single))
    }

    /** Δώδεκα ισόποσες δόσεις: ο λόγος το λέει κι εκεί. */
    @Test
    fun `δώδεκα ισόποσες δόσεις μετρώνται σωστά`() {
        val twelve = VAT_AUGUST
            .replace("Ποσό δόσης δήλωσης της 30/09/2026 1.771,59 €", "Ποσό δόσης δήλωσης της 30/09/2026 295,26 €")
        val plan = AadeInstallmentParser.parse(twelve) ?: error("δεν αναγνωρίστηκε")
        assertEquals(12, plan.installmentCount)
        assertEquals(12, InstallmentPlan.of(plan).allowedCount())
    }

    /** Ο χρήστης δεν μπορεί να ζητήσει περισσότερες από όσες λέει το έντυπο. */
    @Test
    fun `ο μέγιστος αριθμός δόσεων βγαίνει από τα ποσά`() {
        assertEquals(2, InstallmentPlan(3543.17, 1771.59, 2, null).allowedCount())
        assertEquals(1, InstallmentPlan(49.75, 49.75, 1, null).allowedCount())
        // Ποσά που δεν βγάζουν καθαρό λόγο: μένει ό,τι είχε διαβαστεί
        assertEquals(3, InstallmentPlan(1000.0, 137.0, 3, null).allowedCount())
    }

    private companion object {
        val VAT_AUGUST = """
            9/11/26, 3:18 PM TAXISnet
            TAXISnet - Υπηρεσίες Πληρωμής
            Σημείωμα για Πληρωμή
            Στοιχεία Οφειλέτη
            Α.Φ.Μ. 802576637
            Ονοματεπώνυμο/ Επωνυμία ΤΟ ΒΑΨΙΜΟ Ε Ε
            Στοιχεία Οφειλής
            Τύπος Πληρωμής Πληρωμή Βεβαιωμένων Οφειλών εκτός Ρύθμισης
            ΔΟΥ ΚΕ.Β.ΕΙΣ. ΑΤΤΙΚΗΣ
            Είδος Φόρου ΧΡΕΩΣΤΙΚΕΣ ΔΗΛΩΣΕΙΣ ΦΠΑ ΜΕΣΩ INTERNET
            Ημερολογιακή Περίοδος 01/08/2026-31/08/2026
            Συνολικό ποσό οφειλής δήλωσης 3.543,17 €
            Ποσό δόσης δήλωσης της 30/09/2026 1.771,59 €
            Ταυτότητα Οφειλής 802576637 910102202 629053900890
            Ημ/νία Έκδοσης 11/09/2026
            Προσοχή:
            Θα πρέπει να πληρώσετε το ποσό της 1ης δόσης  μέχρι τις 30/09/2026 .
        """.trimIndent()
    }
}
