package gr.prosfora.app.data.db

import java.time.LocalDate
import java.time.YearMonth

/** Ένα διάστημα που ο εργαζόμενος δούλευε. Χωρίς [end] δουλεύει ακόμη. */
data class EmploymentPeriod(val start: LocalDate, val end: LocalDate?) {
    fun contains(day: LocalDate): Boolean =
        !day.isBefore(start) && (end == null || !day.isAfter(end))
}

/**
 * Οι περίοδοι απασχόλησης ενός εργαζόμενου.
 *
 * Ο ίδιος άνθρωπος μπορεί να φύγει και να ξαναέρθει, οπότε μία «ημερομηνία
 * αποχώρησης» δεν αρκεί: κρατιέται μια λίστα από διαστήματα εργασίας, και ό,τι
 * πέφτει ανάμεσά τους είναι παύση.
 *
 * Αποθηκεύεται ως κείμενο που διαβάζεται και μέσα στο φύλλο του Drive:
 * `2024-03-01..2024-08-31;2025-01-10..` — το τελευταίο χωρίς τέλος σημαίνει
 * ότι δουλεύει ακόμη.
 */
object EmploymentPeriods {

    fun parse(text: String): List<EmploymentPeriod> = text
        .split(';')
        .mapNotNull { part ->
            val bounds = part.trim().split("..")
            if (bounds.size != 2) return@mapNotNull null
            val start = runCatching { LocalDate.parse(bounds[0].trim()) }.getOrNull()
                ?: return@mapNotNull null
            val end = bounds[1].trim().takeIf { it.isNotEmpty() }
                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: return@mapNotNull null }
            if (end != null && end.isBefore(start)) null else EmploymentPeriod(start, end)
        }
        .let(::normalize)

    fun format(periods: List<EmploymentPeriod>): String =
        normalize(periods).joinToString(";") { "${it.start}..${it.end ?: ""}" }

    /**
     * Ταξινομημένες, χωρίς επικαλύψεις. Δύο διαστήματα που ακουμπάνε —το ένα
     * τελειώνει 31/5, το άλλο αρχίζει 1/6— είναι μία συνεχής απασχόληση.
     */
    fun normalize(periods: List<EmploymentPeriod>): List<EmploymentPeriod> {
        val sorted = periods.sortedBy { it.start }
        val out = mutableListOf<EmploymentPeriod>()
        for (next in sorted) {
            val last = out.lastOrNull()
            if (last == null) {
                out += next
                continue
            }
            val lastEnd = last.end
            if (lastEnd == null) continue // το ανοιχτό απορροφά ό,τι ακολουθεί
            if (!next.start.isAfter(lastEnd.plusDays(1))) {
                val end = if (next.end == null) null else maxOf(lastEnd, next.end)
                out[out.lastIndex] = last.copy(end = end)
            } else {
                out += next
            }
        }
        return out
    }

    /** Ενεργός από [from] ως [to] (χωρίς [to]: από εκεί και πέρα). */
    fun mark(periods: List<EmploymentPeriod>, from: LocalDate, to: LocalDate?): List<EmploymentPeriod> =
        normalize(periods + EmploymentPeriod(minOf(from, to ?: from), to?.let { maxOf(from, it) }))

    /** Παύση από [from] ως [to] (χωρίς [to]: από εκεί και πέρα). */
    fun unmark(periods: List<EmploymentPeriod>, from: LocalDate, to: LocalDate?): List<EmploymentPeriod> {
        val lo = if (to == null) from else minOf(from, to)
        val hi = to?.let { maxOf(from, it) }
        return normalize(
            periods.flatMap { p ->
                val overlaps = (hi == null || !p.start.isAfter(hi)) && (p.end == null || !p.end.isBefore(lo))
                if (!overlaps) return@flatMap listOf(p)
                buildList {
                    if (p.start.isBefore(lo)) add(EmploymentPeriod(p.start, lo.minusDays(1)))
                    if (hi != null && (p.end == null || p.end.isAfter(hi))) add(EmploymentPeriod(hi.plusDays(1), p.end))
                }
            },
        )
    }

    fun isActive(periods: List<EmploymentPeriod>, day: LocalDate): Boolean = periods.any { it.contains(day) }

    /** Η τελευταία μέρα εργασίας, αν έχει φύγει· `null` αν δουλεύει ακόμη ή δεν υπάρχουν περίοδοι. */
    fun leftDay(periods: List<EmploymentPeriod>): LocalDate? = normalize(periods).lastOrNull()?.end

    /**
     * Η αποχώρηση όπως την ορίζει το απλό πεδίο «Αποχώρηση», μεταφρασμένη σε
     * περιόδους.
     *
     * - Χωρίς ημερομηνία: δεν έφυγε — η τελευταία περίοδος ξανανοίγει.
     * - Με ημερομηνία: ό,τι μετά από αυτήν γίνεται παύση. Αν η τελευταία
     *   περίοδος είχε κλείσει νωρίτερα, επεκτείνεται ως εκεί — όποιος ορίζει
     *   αποχώρηση λέει ότι ως τότε δούλευε.
     */
    fun withLeftDay(periods: List<EmploymentPeriod>, left: LocalDate?): List<EmploymentPeriod> {
        val current = normalize(periods)
        val last = current.lastOrNull() ?: return current
        if (left == null) return current.dropLast(1) + last.copy(end = null)
        val lastEnd = last.end
        if (lastEnd != null && left.isAfter(lastEnd)) {
            return current.dropLast(1) + last.copy(end = left)
        }
        return unmark(current, left.plusDays(1), null)
    }

    /** Επαναπρόσληψη: νέα ανοιχτή περίοδος από [day]. */
    fun rehire(periods: List<EmploymentPeriod>, day: LocalDate): List<EmploymentPeriod> = mark(periods, day, null)

    /** Ημέρες εργασίας ως [today], για τη σύνοψη της καρτέλας. */
    fun workedDays(periods: List<EmploymentPeriod>, today: LocalDate): Long =
        normalize(periods).sumOf { p ->
            if (p.start.isAfter(today)) 0L
            else minOf(p.end ?: today, today).toEpochDay() - p.start.toEpochDay() + 1
        }

    /**
     * Πρόταση από τις μισθοδοσίες, όταν ο χρήστης δεν έχει ορίσει ακόμη τίποτα.
     *
     * Κάθε μήνας με μισθοδοσία μετράει ολόκληρος, και οι συνεχόμενοι μήνες
     * ενώνονται· ένας μήνας χωρίς μισθοδοσία ανάμεσα είναι παύση. Αν η τελευταία
     * μισθοδοσία είναι πρόσφατη και δεν έχει οριστεί αποχώρηση, ο εργαζόμενος
     * θεωρείται ότι δουλεύει ακόμη. Μένει πρόταση ως να την αποθηκεύσει ο χρήστης.
     */
    fun suggestFromPayroll(
        months: Collection<YearMonth>,
        left: LocalDate?,
        today: LocalDate,
    ): List<EmploymentPeriod> {
        val lastMonth = months.maxOrNull() ?: return emptyList()
        val periods = normalize(months.distinct().map { EmploymentPeriod(it.atDay(1), it.atEndOfMonth()) })
        val recent = !lastMonth.isBefore(YearMonth.from(today).minusMonths(2))
        return when {
            left != null -> withLeftDay(periods, left)
            recent -> withLeftDay(periods, null)
            else -> periods
        }
    }
}
