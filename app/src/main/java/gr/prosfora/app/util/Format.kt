package gr.prosfora.app.util

import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Ελληνική μορφοποίηση, ίδια με του PDF: 1.892,99 € */
private val greek = Locale.forLanguageTag("el-GR")
private val symbols = DecimalFormatSymbols(greek).apply {
    decimalSeparator = ','
    groupingSeparator = '.'
}

private val money = DecimalFormat("#,##0.00", symbols)
private val number = DecimalFormat("#,##0.00", symbols)
private val dateFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("d/M/yyyy", greek)

fun Double.asMoney(): String = "${money.format(this)} €"

fun Double.asNumber(): String = number.format(this)

fun Long.asOfferDate(): String = LocalDate.ofEpochDay(this).format(dateFormat)

fun LocalDate.asOfferDate(): String = format(dateFormat)

/**
 * Δέχεται και κόμμα και τελεία ως υποδιαστολή — το πληκτρολόγιο δίνει ό,τι να 'ναι.
 * Με "1.234,56" η τελεία είναι διαχωριστικό χιλιάδων· με "12.5" είναι υποδιαστολή.
 */
fun String.parseDecimal(): Double? {
    val raw = trim()
    if (raw.isEmpty()) return null
    val normalized = when {
        raw.contains(',') && raw.contains('.') -> raw.replace(".", "").replace(',', '.')
        raw.contains(',') -> raw.replace(',', '.')
        else -> raw
    }
    return normalized.toDoubleOrNull()
}
