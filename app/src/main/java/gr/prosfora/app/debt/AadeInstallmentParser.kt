package gr.prosfora.app.debt

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.round

/** Ακριβής αναγνώριση ΑΑΔΕ οφειλών, ΑΦΜ και πλάνων δόσεων. */
object AadeInstallmentParser {
    private const val EXPECTED_AFM = "802576637"
    private const val GAP = "[\\s\\S]{0,120}?"
    private val AMOUNT = Regex("([0-9][0-9.]*,[0-9]{2})")
    private val ANY_AMOUNT = Regex("[0-9][0-9.]*,[0-9]{2}")
    private val DATE = Regex("(\\d{1,2}/\\d{1,2}/\\d{4})")

    data class Info(
        val totalAmount: Double,
        val installmentAmount: Double,
        val installmentCount: Int,
        val firstDueDay: Long,
    )

    fun isAadeDocument(text: String): Boolean {
        val n = normalize(text)
        return n.contains("ΤΑΥΤΟΤΗΤΑ ΟΦΕΙΛΗΣ") ||
            n.contains("ΣΗΜΕΙΩΜΑ ΓΙΑ ΠΛΗΡΩΜΗ") ||
            n.contains("ΣΥΝΟΛΙΚΟ ΠΟΣΟ ΟΦΕΙΛΗΣ") ||
            n.contains("ΠΟΣΟ ΔΟΣΗΣ ΔΗΛΩΣΗΣ")
    }

    /**
     * Ελέγχει το ΑΦΜ χωρίς να απαιτεί συγκεκριμένη μορφοποίηση του OCR.
     * Αν υπάρχει η ετικέτα ΑΦΜ/Αριθμός Φορολογικού Μητρώου, απαιτείται το σωστό ΑΦΜ.
     * Ως εφεδρεία αναγνωρίζει και το 802576637 με κενά ανάμεσα στα ψηφία.
     */
    fun afmMatches(text: String): Boolean {
        val n = normalize(text)
        val compactExpected = EXPECTED_AFM.toCharArray().joinToString("\\s*")
        if (Regex("(?<!\\d)$compactExpected(?!\\d)").containsMatchIn(n)) return true

        val labelled = listOf(
            Regex("\\bΑΦΜ\\b[\\s:.-]{0,40}([0-9][0-9\\s.-]{8,30})"),
            Regex("ΑΡΙΘΜΟΣ\\s+ΦΟΡΟΛΟΓΙΚΟΥ\\s+ΜΗΤΡΩΟΥ[\\s:.-]{0,40}([0-9][0-9\\s.-]{8,30})"),
        )
        val values = labelled.flatMap { regex ->
            regex.findAll(n).mapNotNull { match ->
                match.groupValues[1].filter(Char::isDigit).takeIf { it.length in 9..12 }
            }.toList()
        }
        return EXPECTED_AFM in values
    }

    /** Αναγνωρίζει συνολικό ποσό, ποσό δόσης και την ημερομηνία της πρώτης δόσης. */
    fun parse(text: String): Info? {
        if (!isAadeDocument(text) || !afmMatches(text)) return null
        val n = normalize(text)

        val total = findAmountAfterLabel(
            n,
            listOf("ΣΥΝΟΛΙΚΟ ΠΟΣΟ ΟΦΕΙΛΗΣ", "ΣΥΝΟΛΙΚΟ ΠΟΣΟ ΟΦΕΙΛΗΣ ΔΗΛΩΣΗΣ"),
        ) ?: orderedAmount(n, 0)

        val installmentMatch = Regex(
            "ΠΟΣΟ\\s+ΔΟΣΗΣ\\s+ΔΗΛΩΣΗΣ\\s+ΤΗΣ" +
                "\\s*" + DATE.pattern + GAP + AMOUNT.pattern,
        ).find(n)

        val installmentDate = installmentMatch?.groupValues?.getOrNull(1)
            ?: Regex("ΠΟΣΟ\\s+ΔΟΣΗΣ\\s+ΔΗΛΩΣΗΣ.*?" + DATE.pattern).find(n)?.groupValues?.getOrNull(1)
        val installmentAmount = installmentMatch?.groupValues?.last()?.let(::money)
            ?: findAmountAfterLabel(n, listOf("ΠΟΣΟ ΔΟΣΗΣ", "ΠΟΣΟ ΔΟΣΗΣ ΔΗΛΩΣΗΣ"))
            ?: orderedAmount(n, 1)

        val totalValue = total ?: return null
        val doseValue = installmentAmount ?: return null
        val due = installmentDate?.let(::parseDate) ?: return null

        if (totalValue <= 0.0 || doseValue <= 0.0 || totalValue + 0.01 < doseValue) return null

        val rawCount = totalValue / doseValue
        val count = round(rawCount).toInt()
        if (count < 2 || abs(rawCount - count) > 0.02) return null

        return Info(
            totalAmount = round2(totalValue),
            installmentAmount = round2(doseValue),
            installmentCount = count,
            firstDueDay = due,
        )
    }

    private fun findAmountAfterLabel(text: String, labels: List<String>): Double? {
        for (label in labels) {
            Regex(Regex.escape(label) + GAP + AMOUNT.pattern).find(text)?.groupValues?.last()?.let { raw ->
                money(raw)?.let { return it }
            }
        }
        return null
    }

    private fun orderedAmount(text: String, index: Int): Double? =
        ANY_AMOUNT.findAll(text).mapNotNull { money(it.value) }.toList().getOrNull(index)

    private fun normalize(text: String): String = buildString(text.length) {
        text.forEach { ch ->
            append(
                when (ch) {
                    'ά', 'Ά' -> 'Α'
                    'έ', 'Έ' -> 'Ε'
                    'ή', 'Ή' -> 'Η'
                    'ί', 'ΐ', 'ϊ', 'Ί', 'Ϊ' -> 'Ι'
                    'ό', 'Ό' -> 'Ο'
                    'ύ', 'ΰ', 'ϋ', 'Ύ', 'Ϋ' -> 'Υ'
                    'ώ', 'Ώ' -> 'Ω'
                    'ς' -> 'Σ'
                    else -> ch.uppercaseChar()
                },
            )
        }
    }

    private fun money(raw: String): Double? = raw
        .replace(".", "")
        .replace(',', '.')
        .toDoubleOrNull()

    private fun parseDate(raw: String): Long? = runCatching {
        LocalDate.parse(raw, DateTimeFormatter.ofPattern("d/M/uuuu")).toEpochDay()
    }.getOrNull()

    private fun round2(value: Double): Double = round(value * 100.0) / 100.0
}
