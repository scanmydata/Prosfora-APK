package gr.prosfora.app.mail

import gr.prosfora.app.settings.SmtpSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Αποστολή email απευθείας μέσω SMTP — αντικαθιστά το AppSheet bot που δεν
 * επιτρεπόταν στο free tier. Κανένας ενδιάμεσος server.
 */
object MailSender {

    data class Outgoing(
        val to: String,
        val subject: String,
        val body: String,
        val attachment: File? = null,
        val attachmentName: String? = null,
    )

    suspend fun send(settings: SmtpSettings, message: Outgoing) = withContext(Dispatchers.IO) {
        require(settings.isConfigured) { "Δεν έχουν συμπληρωθεί οι ρυθμίσεις SMTP" }
        require(message.to.isNotBlank()) { "Λείπει ο παραλήπτης" }

        val session = Session.getInstance(buildProperties(settings), object : Authenticator() {
            override fun getPasswordAuthentication() =
                PasswordAuthentication(settings.username, settings.password)
        })

        val mime = MimeMessage(session).apply {
            setFrom(InternetAddress(settings.fromAddress, settings.fromName, "UTF-8"))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(message.to))
            setSubject(message.subject, "UTF-8")
            sentDate = java.util.Date()
        }

        val textPart = MimeBodyPart().apply { setText(message.body, "UTF-8") }

        if (message.attachment != null) {
            val filePart = MimeBodyPart().apply {
                dataHandler = DataHandler(FileDataSource(message.attachment))
                fileName = message.attachmentName ?: message.attachment.name
            }
            mime.setContent(MimeMultipart().apply { addBodyPart(textPart); addBodyPart(filePart) })
        } else {
            mime.setContent(MimeMultipart().apply { addBodyPart(textPart) })
        }

        Transport.send(mime)
    }

    /** Δοκιμή σύνδεσης χωρίς να σταλεί μήνυμα — για το κουμπί «Δοκιμή» στις ρυθμίσεις. */
    suspend fun verify(settings: SmtpSettings) = withContext(Dispatchers.IO) {
        val session = Session.getInstance(buildProperties(settings))
        session.getTransport("smtp").use { transport ->
            transport.connect(settings.host, settings.port, settings.username, settings.password)
        }
    }

    private fun buildProperties(settings: SmtpSettings) = Properties().apply {
        put("mail.smtp.host", settings.host)
        put("mail.smtp.port", settings.port.toString())
        put("mail.smtp.auth", "true")
        put("mail.smtp.connectiontimeout", "20000")
        put("mail.smtp.timeout", "20000")
        put("mail.smtp.writetimeout", "20000")
        if (settings.useSsl) {
            put("mail.smtp.ssl.enable", "true")
            put("mail.smtp.socketFactory.port", settings.port.toString())
            put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
        } else if (settings.useStartTls) {
            put("mail.smtp.starttls.enable", "true")
            put("mail.smtp.starttls.required", "true")
        }
    }

    private inline fun <T : javax.mail.Service, R> T.use(block: (T) -> R): R = try {
        block(this)
    } finally {
        runCatching { close() }
    }
}
