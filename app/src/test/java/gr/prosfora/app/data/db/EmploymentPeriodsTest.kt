package gr.prosfora.app.data.db

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

/**
 * Περίοδοι απασχόλησης: ο ίδιος άνθρωπος φεύγει, σταματάει, ξαναέρχεται.
 */
class EmploymentPeriodsTest {

    private fun d(text: String) = LocalDate.parse(text)
    private fun p(from: String, to: String?) = EmploymentPeriod(d(from), to?.let(::d))

    @Test
    fun `η μορφή διαβάζεται και ξαναγράφεται ίδια`() {
        val text = "2024-03-01..2024-08-31;2025-01-10.."
        assertEquals(text, EmploymentPeriods.format(EmploymentPeriods.parse(text)))
    }

    @Test
    fun `σκουπίδια στο κελί δεν ρίχνουν την ανάγνωση`() {
        val parsed = EmploymentPeriods.parse("χθες..αύριο;2025-01-10..;2025-05-01..2025-04-01")
        assertEquals(listOf(p("2025-01-10", null)), parsed)
    }

    /** 31/5 και 1/6 ακουμπάνε: είναι μία συνεχής απασχόληση, όχι δύο. */
    @Test
    fun `διαστήματα που ακουμπάνε ενώνονται`() {
        val merged = EmploymentPeriods.normalize(listOf(p("2025-06-01", "2025-08-31"), p("2025-01-01", "2025-05-31")))
        assertEquals(listOf(p("2025-01-01", "2025-08-31")), merged)
    }

    @Test
    fun `το ανοιχτό διάστημα απορροφά ό,τι ακολουθεί`() {
        val merged = EmploymentPeriods.normalize(listOf(p("2025-01-01", null), p("2025-06-01", "2025-06-30")))
        assertEquals(listOf(p("2025-01-01", null)), merged)
    }

    /** Παύση στη μέση μιας απασχόλησης που συνεχίζεται: δύο κομμάτια. */
    @Test
    fun `παύση στη μέση χωρίζει την περίοδο στα δύο`() {
        val result = EmploymentPeriods.unmark(listOf(p("2025-01-01", null)), d("2025-04-01"), d("2025-04-30"))
        assertEquals(listOf(p("2025-01-01", "2025-03-31"), p("2025-05-01", null)), result)
        assertFalse(EmploymentPeriods.isActive(result, d("2025-04-15")))
        assertTrue(EmploymentPeriods.isActive(result, d("2025-05-15")))
    }

    @Test
    fun `το μαρκάρισμα γεμίζει μια παύση`() {
        val paused = listOf(p("2025-01-01", "2025-03-31"), p("2025-05-01", null))
        assertEquals(listOf(p("2025-01-01", null)), EmploymentPeriods.mark(paused, d("2025-04-01"), d("2025-04-30")))
    }

    /** Ο χρήστης μπορεί να πατήσει πρώτα την τελευταία μέρα. */
    @Test
    fun `η σειρά των δύο πατημάτων δεν μετράει`() {
        assertEquals(
            EmploymentPeriods.mark(emptyList(), d("2025-01-01"), d("2025-02-01")),
            EmploymentPeriods.mark(emptyList(), d("2025-02-01"), d("2025-01-01")),
        )
    }

    @Test
    fun `αποχώρηση κλείνει την ανοιχτή περίοδο`() {
        val result = EmploymentPeriods.withLeftDay(listOf(p("2024-01-01", null)), d("2025-07-15"))
        assertEquals(listOf(p("2024-01-01", "2025-07-15")), result)
        assertEquals(d("2025-07-15"), EmploymentPeriods.leftDay(result))
    }

    @Test
    fun `καθαρισμός αποχώρησης ξανανοίγει την τελευταία περίοδο`() {
        val result = EmploymentPeriods.withLeftDay(listOf(p("2024-01-01", "2025-07-15")), null)
        assertNull(EmploymentPeriods.leftDay(result))
        assertTrue(EmploymentPeriods.isActive(result, d("2026-01-01")))
    }

    /** Όποιος ορίζει αποχώρηση αργότερα από το τέλος, λέει ότι ως τότε δούλευε. */
    @Test
    fun `αποχώρηση μετά το τέλος επεκτείνει την περίοδο`() {
        val result = EmploymentPeriods.withLeftDay(listOf(p("2025-01-01", "2025-05-31")), d("2025-06-30"))
        assertEquals(listOf(p("2025-01-01", "2025-06-30")), result)
    }

    /** Ο ίδιος εργαζόμενος ξαναπροσλαμβάνεται: η παλιά περίοδος μένει ως έχει. */
    @Test
    fun `επαναπρόσληψη προσθέτει νέα ανοιχτή περίοδο`() {
        val result = EmploymentPeriods.rehire(listOf(p("2024-01-01", "2024-06-30")), d("2025-03-01"))
        assertEquals(listOf(p("2024-01-01", "2024-06-30"), p("2025-03-01", null)), result)
        assertNull(EmploymentPeriods.leftDay(result))
    }

    @Test
    fun `οι ημέρες εργασίας μετράνε ως σήμερα`() {
        val periods = listOf(p("2025-01-01", "2025-01-10"), p("2025-02-01", null))
        assertEquals(10L + 5L, EmploymentPeriods.workedDays(periods, d("2025-02-05")))
    }

    /** Μήνας χωρίς μισθοδοσία ανάμεσα σε δύο με μισθοδοσία είναι παύση. */
    @Test
    fun `η πρόταση από μισθοδοσίες βάζει παύση στο κενό`() {
        val months = listOf(YearMonth.of(2025, 1), YearMonth.of(2025, 2), YearMonth.of(2025, 4))
        val suggested = EmploymentPeriods.suggestFromPayroll(months, left = null, today = d("2025-05-10"))
        assertEquals(listOf(p("2025-01-01", "2025-02-28"), p("2025-04-01", null)), suggested)
    }

    /** Παλιά τελευταία μισθοδοσία χωρίς αποχώρηση: δεν τον υποθέτουμε ενεργό. */
    @Test
    fun `παλιά μισθοδοσία δεν γίνεται ανοιχτή περίοδος`() {
        val suggested = EmploymentPeriods.suggestFromPayroll(listOf(YearMonth.of(2024, 3)), null, d("2025-05-10"))
        assertEquals(listOf(p("2024-03-01", "2024-03-31")), suggested)
    }
}
