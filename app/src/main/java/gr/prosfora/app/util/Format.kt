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

/**
 * Το «Είδος» γράφεται συχνά ως «Χρωματισμός διαμερίσματος». Στο PDF και στα
 * μηνύματα η λέξη περισσεύει, γιατί το κείμενο λέει ήδη «προσφορά
 * ελαιοχρωματισμών για την …». Αφαιρείται σε όποια πτώση κι αν είναι.
 */
fun String.strippedKind(): String =
    replace(Regex("""χρωματισμ\S*""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s{2,}"""), " ")
        .trim()
        .trimStart('-', '–', ',')
        .trim()

/** Η διεύθυνση τυπώνεται με κεφαλαία στο PDF, όπως στο πρότυπο. */
fun String.upperGreek(): String = uppercase(java.util.Locale.forLanguageTag("el-GR"))

/** Σύντομη σφραγίδα χρόνου για «στάλθηκε στις …». */
fun Long.asSentStamp(): String = java.text.DateFormat
    .getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT, greek)
    .format(java.util.Date(this))
