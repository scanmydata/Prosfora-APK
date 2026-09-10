package gr.prosfora.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Αλλάζω ψευδώνυμο και ο συγχρονισμός μου το ξαναφέρνει.»
 *
 * Κάθε έλεγχος εδώ είναι ένας από τους τρόπους με τους οποίους συνέβαινε.
 */
class EmployeeEditsTest {

    private fun employee(
        alias: String = "",
        editedAt: Long = 0L,
        updatedAt: Long = 0L,
        leftDay: Long? = null,
        editedBy: String = "",
    ) = EmployeeEntity(
        id = "123",
        amIka = "123",
        name = "BUTT HURARA",
        alias = alias,
        leftDay = leftDay,
        updatedAt = updatedAt,
        editedAt = editedAt,
        editedBy = editedBy,
    )

    /**
     * Η αυτόματη ενημέρωση από τη μισθοδοσία χτυπάει το [EmployeeEntity.updatedAt]
     * — παλιά αυτό αρκούσε για να κερδίσει η παλιά γραμμή του φύλλου.
     */
    @Test
    fun `η νέα αλλαγή του χρήστη κερδίζει όσο κι αν ενημερώθηκε το φύλλο`() {
        val local = employee(alias = "Μπούτ", editedAt = 2_000, updatedAt = 500)
        val sheet = employee(alias = "Παλιό", editedAt = 1_000, updatedAt = 9_999)
        assertFalse(EmployeeEdits.remoteWins(local, sheet))
    }

    @Test
    fun `η νεότερη αλλαγή του άλλου χρήστη έρχεται`() {
        val local = employee(alias = "Παλιό", editedAt = 1_000)
        val sheet = employee(alias = "Νέο", editedAt = 2_000)
        assertTrue(EmployeeEdits.remoteWins(local, sheet))
    }

    /** Ισοπαλία στο μηδέν, πριν από την αναβάθμιση: γεμίζουν μόνο τα κενά. */
    @Test
    fun `πριν την αναβάθμιση το φύλλο γεμίζει μόνο τα κενά`() {
        val filled = EmployeeEdits.fillBlanks(employee(alias = ""), employee(alias = "Μπούτ"))
        assertEquals("Μπούτ", filled?.alias)
        assertNull(EmployeeEdits.fillBlanks(employee(alias = "Δικό μου"), employee(alias = "Άλλο")))
    }

    @Test
    fun `η αποχώρηση ανακοινώνεται στους άλλους`() {
        val local = employee(editedAt = 1_000)
        val sheet = employee(editedAt = 2_000, leftDay = 20_000, editedBy = "synergatis@gmail.com")
        assertEquals(20_000L, EmployeeEdits.departureToAnnounce(local, sheet, me = "adonis@gmail.com"))
    }

    /** Ο συντάκτης δεν ειδοποιείται — ούτε σε δεύτερη συσκευή του. */
    @Test
    fun `ο συντάκτης δεν ειδοποιείται για τη δική του αποχώρηση`() {
        val local = employee(editedAt = 1_000)
        val sheet = employee(editedAt = 2_000, leftDay = 20_000, editedBy = "Adonis@Gmail.com")
        assertNull(EmployeeEdits.departureToAnnounce(local, sheet, me = "adonis@gmail.com"))
    }

    @Test
    fun `ίδια αποχώρηση δεν ξαναανακοινώνεται`() {
        val local = employee(editedAt = 1_000, leftDay = 20_000)
        val sheet = employee(editedAt = 2_000, leftDay = 20_000, alias = "άλλαξε μόνο αυτό")
        assertNull(EmployeeEdits.departureToAnnounce(local, sheet, me = "x"))
    }

    /** Παλιότερη αλλαγή στο φύλλο δεν εφαρμόζεται, άρα δεν ανακοινώνεται. */
    @Test
    fun `παλιότερη αποχώρηση από το φύλλο δεν ανακοινώνεται`() {
        val local = employee(editedAt = 3_000)
        val sheet = employee(editedAt = 2_000, leftDay = 20_000)
        assertNull(EmployeeEdits.departureToAnnounce(local, sheet, me = "x"))
    }
}
