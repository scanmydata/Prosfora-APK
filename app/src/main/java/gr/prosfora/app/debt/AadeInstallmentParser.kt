package gr.prosfora.app.debt

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.round

/**
 * Ειδικός parser για το τμήμα των σημειωμάτων ΑΑΔΕ που αφορά δόσεις.
 * Κρατάει ξεχωριστό το συνολικό ποσό από το ποσό της δόσης ώστε η εισαγωγή
 * να μπορεί να προτείνει είτε μία συνολική οφειλή είτε ξεχωριστές δόσεις.
 */
object AadeInstallmentParser {

    private const val EXPECTED_AFM = "802576637"
    private const val AMOUNT_PATTERN = "([0-9][0-9.]*,[0-9]{2})"
    private const val GAP = "[\\s\\S]{0,80}?"

    data class Info(
        val totalAmount: Double,
        val installmentAmount: Double,
        val installmentCount: Int,
        val firstDueDay: Long,
    )

    fun isAadeDocument(text: String): Boolean {
        val normalized = text
            .replace("Ά", "Α")
            .replace("Έ", "Ε")
            .replace("Ή", "Η")
            .replace("Ί", "Ι")
            .replace("Ό", "Ο")
            .replace("Ύ", "Υ")
            .replace("Ώ", "Ω")
            .uppercase()
        return normalized.contains("ΤΑΥΤΟΤΗΤΑ ΟΦΕΙΛΗΣ") ||
            normalized.contains("ΣΗΜΕΙΩΜΑ ΓΙΑ ΠΛΗΡΩΜΗ") ||
            normalized.contains("ΣΥΝΟΛΙΚΟ ΠΟΣΟ ΟΦΕΙΛΗΣ")
    }

    fun afmMatches(text: String): Boolean {
        val normalized = normalize(text)
        val matches = buildList {
            addAll(Regex("\\bΑΦΜ\\b[\\s:.-]{0,20}(\\d{9})").findAll(normalized).map { it.groupValues[1] }.toList())
            addAll(Regex("ΑΡΙΘΜΟΣ\\s+ΦΟΡΟΛΟΓΙΚΟΥ\\s+ΜΗΤΡΩΟΥ[\\s:.-]{0,20}(\\d{9})").findAll(normalized).map { it.groupValues[1] }.toList())
        }
        return EXPECTED_AFM in matches
    }

    fun parse(text: String): Info? {
        if (!isAadeDocument(text) || !afmMatches(text)) return null
        val normalized = normalize(text)

        val total = Regex(
            "ΣΥΝΟΛΙΚΟ\\s+ΠΟΣΟ\\s+ΟΦΕΙΛΗΣ" + GAP + AMOUNT_PATTERN,
        ).find(normalized)?.groupValues?.last()?.let(::money) ?: return null

        val installment = Regex(
            "ΠΟΣΟ\\s+ΔΟΣΗΣ\\s+ΔΗΛΩΣΗΣ\\s+ΤΗΣ" +
                "[\\s]*(\\d{1,2}/\\d{1,2}/\\d{4})" +
                GAP + AMOUNT_PATTERN,
        ).find(normalized) ?: return null

        val firstDueDay = parseDate(installment.groupValues[1]) ?: return null
        val installmentAmount = money(installment.groupValues.last()) ?: return null
        if (total <= 0.0 || installmentAmount <= 0.0 || total + 0.01 < installmentAmount) return null

        val rawCount = total / installmentAmount
        val count = round(rawCount).toInt()
        if (count < 2 || abs(rawCount - count) > 0.01) return null

        return Info(
            totalAmount = round2(total),
            installmentAmount = round2(installmentAmount),
            installmentCount = count,
            firstDueDay = firstDueDay,
        )
    }

    private fun normalize(text: String): String = buildString(text.length) {
        text.forEach { ch ->
            append(
                when (ch.uppercaseChar()) {
                    'Ά' -> 'Α'
                    'Έ' -> 'Ε'
                    'Ή' -> 'Η'
                    'Ί', 'Ϊ' -> 'Ι'
                    'Ό' -> 'Ο'
                    'Ύ', 'Ϋ' -> 'Υ'
                    'Ώ' -> 'Ω'
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
