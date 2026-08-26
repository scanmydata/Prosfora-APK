package gr.prosfora.app.mail

import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.message.GreetingOptions
import gr.prosfora.app.message.MessageTemplates
import gr.prosfora.app.settings.SmtpSettings
import java.io.File

/**
 * Χτίζει το email μιας προσφοράς.
 *
 * Η αντικατάσταση των πεδίων γίνεται **αποκλειστικά** από το [MessageTemplates],
 * όπως και σε SMS και Viber. Παλιότερα εδώ υπήρχε δεύτερη, μικρότερη λίστα
 * πεδίων· έτσι το `{χαιρετισμός}` έφτανε αυτούσιο στο email ενώ δούλευε παντού
 * αλλού.
 */
object OfferMail {

    fun subject(
        template: String,
        details: OfferWithDetails,
        greeting: GreetingOptions = GreetingOptions(),
    ): String = MessageTemplates.render(template, details, greeting = greeting)

    fun body(
        template: String,
        details: OfferWithDetails,
        settings: SmtpSettings,
        greeting: GreetingOptions = GreetingOptions(),
    ): String =
        buildString {
            append(MessageTemplates.render(template, details, greeting = greeting).trimEnd())
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
