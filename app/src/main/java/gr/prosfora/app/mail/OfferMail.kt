package gr.prosfora.app.mail

import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.settings.SmtpSettings
import java.io.File

/**
 * Αναπαράγει ακριβώς το email του AppSheet bot `Send_Offer_Email`
 * (task `Trigger_On_Click Task - 1`) — βλ. docs/phase0-appsheet-schema.md.
 */
object OfferMail {

    fun subject(details: OfferWithDetails): String =
        "Προσφορά ελαιοχρωματισμών ${details.offer.address}"

    fun attachmentName(details: OfferWithDetails): String =
        "ΠΡΟΣΦΟΡΑ ΕΛΑΙΟΧΡΩΜΑΤΙΣΜΩΝ ${details.offer.address}.pdf"

    fun body(details: OfferWithDetails, settings: SmtpSettings): String {
        val kind = details.offer.kind.ifBlank { "κατοικίας" }
        return buildString {
            append("Καλησπέρα, \n\n")
            append("Σας αποστέλλω την προσφορά για το χρωματισμό της $kind σας.\n")
            append("Στη διάθεση σας για οποιαδήποτε επιπλέον πληροφορία χρειαστείτε.\n\n\n\n")
            append(settings.signature)
        }
    }

    fun compose(
        details: OfferWithDetails,
        settings: SmtpSettings,
        pdf: File?,
    ): MailSender.Outgoing = MailSender.Outgoing(
        to = details.offer.email,
        subject = subject(details),
        body = body(details, settings),
        attachment = pdf,
        attachmentName = attachmentName(details),
    )
}
