package gr.prosfora.app.message

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ο έλεγχος email και κινητού, πριν φύγει μήνυμα σε λάθος παραλήπτη. */
class ContactCheckTest {

    @Test
    fun `το κενό δεν είναι λάθος`() {
        assertTrue(ContactCheck.email("").ok)
        assertTrue(ContactCheck.email("   ").ok)
        assertTrue(ContactCheck.mobile("").ok)
    }

    @Test
    fun `σωστά email περνούν`() {
        listOf(
            "nikos@gmail.com",
            "info@tovapsimo.gr",
            "a.b-c+tag@sub.domain.co.uk",
        ).forEach { assertTrue(it, ContactCheck.email(it).ok) }
    }

    /** Το πιο ύπουλο λάθος: το «γ» του ελληνικού πληκτρολογίου σε «gmail». */
    @Test
    fun `ελληνικοί χαρακτήρες στο email πιάνονται`() {
        val verdict = ContactCheck.email("nikos@γmail.com")
        assertFalse(verdict.ok)
        assertTrue(verdict.problem!!.contains("ελληνικοί"))
    }

    @Test
    fun `χαλασμένα email κόβονται`() {
        listOf(
            "nikos.gmail.com",
            "nikos@@gmail.com",
            "@gmail.com",
            "nikos@gmail",
            "nikos@gmail.",
            "nikos@.com",
            "nikos@gmail.c",
            "nikos @gmail.com",
            ".nikos@gmail.com",
            "ni..kos@gmail.com",
        ).forEach { assertFalse(it, ContactCheck.email(it).ok) }
    }

    @Test
    fun `το email καθαρίζεται από κενά στις άκρες`() {
        assertEquals("nikos@gmail.com", ContactCheck.email("  nikos@gmail.com  ").normalized)
    }

    @Test
    fun `ελληνικό κινητό σε κάθε μορφή`() {
        listOf(
            "6941234567",
            "694 123 4567",
            "694-123-4567",
            "+306941234567",
            "+30 694 1234567",
            "00306941234567",
        ).forEach { assertEquals(it, "6941234567", ContactCheck.mobile(it).normalized) }
    }

    @Test
    fun `το σταθερό το λέει με το όνομά του`() {
        val verdict = ContactCheck.mobile("2109876543")
        assertFalse(verdict.ok)
        assertTrue(verdict.problem!!.contains("Σταθερό"))
    }

    @Test
    fun `λάθος πλήθος ψηφίων`() {
        assertFalse(ContactCheck.mobile("694123456").ok)
        assertFalse(ContactCheck.mobile("69412345678").ok)
    }

    @Test
    fun `γράμματα μέσα στον αριθμό δεν περνούν`() {
        assertFalse(ContactCheck.mobile("694Α234567").ok)
    }

    /** Ξένος αριθμός σε διεθνή μορφή: η εφαρμογή δεν ξέρει τους κανόνες του. */
    @Test
    fun `ξένος αριθμός περνά σε διεθνή μορφή`() {
        assertEquals("+4915112345678", ContactCheck.mobile("+49 151 12345678").normalized)
        assertFalse(ContactCheck.mobile("+49 15").ok)
    }
}
