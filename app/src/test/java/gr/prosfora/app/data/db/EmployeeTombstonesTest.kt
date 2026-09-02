package gr.prosfora.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Η ταφόπλακα ενός εργαζόμενου δεν είναι ισόβια.
 *
 * Το σενάριο που έσπασε στην πράξη: ο χρήστης είχε διαγράψει εργαζόμενους «και
 * από τη βάση» για να καθαρίσει το ευρετήριο. Το id τους έμεινε σε ένα τοπικό
 * σύνολο αποκλεισμού, η εξαγωγή προς το κοινόχρηστο φύλλο τους έκοβε, και ο
 * μόνος τρόπος να ξεφύγει κανείς ήταν να ανοίξει ο χρήστης την καρτέλα και να
 * την αποθηκεύσει — δηλαδή ακριβώς όταν του έδινε ψευδώνυμο.
 */
class EmployeeTombstonesTest {

    @Test
    fun `μια ζωντανη μισθοδοσια σηκωνει την ταφοπλακα του ΑΜ ΙΚΑ`() {
        val revived = EmployeeTombstones.revived(
            tombstones = setOf("9320427216"),
            live = listOf(employee(id = "9320427216", amIka = "9320427216")),
        )
        assertEquals(setOf("9320427216"), revived)
    }

    /** Όποιος δεν έδωσε ΑΜ ΙΚΑ στο OCR είχε αποκλειστεί με το όνομά του. */
    @Test
    fun `σηκωνεται και οταν η ταφοπλακα κρατηθηκε με το id, χωρις ΑΜ ΙΚΑ`() {
        val revived = EmployeeTombstones.revived(
            tombstones = setOf("ΚΩΣΤΑΣ ΠΑΠΠΑΣ"),
            live = listOf(employee(id = "ΚΩΣΤΑΣ ΠΑΠΠΑΣ", amIka = "")),
        )
        assertEquals(setOf("ΚΩΣΤΑΣ ΠΑΠΠΑΣ"), revived)
    }

    /** Η διαγραφή κρατά όποιο από τα δύο ήξερε· και τα δύο ξεκλειδώνουν. */
    @Test
    fun `το ΑΜ ΙΚΑ ξεκλειδωνει καρτελα με διαφορετικο id`() {
        val revived = EmployeeTombstones.revived(
            tombstones = setOf("005"),
            live = listOf(employee(id = "Κ12", amIka = "005")),
        )
        assertEquals(setOf("005"), revived)
    }

    /** Όποιος όντως έφυγε μένει διαγραμμένος: δεν έχει ζωντανή μισθοδοσία. */
    @Test
    fun `οποιος δεν εχει ζωντανη μισθοδοσια μενει διαγραμμενος`() {
        val revived = EmployeeTombstones.revived(
            tombstones = setOf("111", "222"),
            live = listOf(employee(id = "111", amIka = "111")),
        )
        assertEquals(setOf("111"), revived)
    }

    /** Κενά δεν ξεκλειδώνουν τίποτα — αλλιώς ένα κενό id τα σήκωνε όλα. */
    @Test
    fun `κενος ΑΜ ΙΚΑ δεν σηκωνει αλλες ταφοπλακες`() {
        val revived = EmployeeTombstones.revived(
            tombstones = setOf("", "333"),
            live = listOf(employee(id = "ΑΛΛΟΣ", amIka = "")),
        )
        assertTrue(revived.isEmpty())
    }

    @Test
    fun `χωρις ταφοπλακες δεν γινεται τιποτα`() {
        assertTrue(
            EmployeeTombstones.revived(emptySet(), listOf(employee("1", "1"))).isEmpty(),
        )
    }

    private fun employee(id: String, amIka: String) = EmployeeEntity(
        id = id,
        amIka = amIka,
        name = "ΔΟΚΙΜΗ",
        updatedAt = 1L,
    )
}
