package gr.prosfora.app.mail

import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.settings.SmtpSettings
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.asOfferDate
import java.io.File

/**
 * Χτίζει το email μιας προσφοράς. Το θέμα και το σώμα είναι πρότυπα που ο
 * χρήστης επεξεργάζεται στις Ρυθμίσεις, με placeholders σε αγκύλες.
 *
 * Αντικαθιστά το AppSheet bot `Send_Offer_Email` που δεν επιτρεπόταν στο free tier.
 */
object OfferMail {

    /** Τα πεδία που μπορεί να χρησιμοποιήσει ο χρήστης μέσα στα πρότυπα. */
    val PLACEHOLDERS = listOf(
        "{διεύθυνση}" to "Οδός / Περιοχή",
        "{είδος}" to "Είδος έργου",
        "{ημερομηνία}" to "Ημερομηνία προσφοράς",
        "{σύνολο}" to "Γενικό σύνολο",
    )

    fun fill(template: String, details: OfferWithDetails): String {
        val offer = details.offer
        return template
            .replace("{διεύθυνση}", offer.address)
            .replace("{είδος}", offer.kind.ifBlank { "κατοικίας" })
            .replace("{ημερομηνία}", offer.dateEpochDay.asOfferDate())
            .replace("{σύνολο}", details.total.asMoney())
    }

    fun subject(template: String, details: OfferWithDetails): String =
        fill(template, details).trim()

    fun body(template: String, details: OfferWithDetails, settings: SmtpSettings): String =
        buildString {
            append(fill(template, details).trimEnd())
            append("\n\n\n")
            append(settings.signature)
        }

    fun attachmentName(details: OfferWithDetails): String =
        "ΠΡΟΣΦΟΡΑ ΕΛΑΙΟΧΡΩΜΑΤΙΣΜΩΝ ${details.offer.address}.pdf".replace('/', '-')

    fun compose(
        to: String,
        subject: String,
        body: String,
        details: OfferWithDetails,
        pdf: File?,
    ): MailSender.Outgoing = MailSender.Outgoing(
        to = to,
        subject = subject,
        body = body,
        attachment = pdf,
        attachmentName = attachmentName(details),
    )
}
