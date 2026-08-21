package gr.prosfora.app.mail

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.activation.DataHandler
import javax.activation.FileDataSource
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart

/**
 * Αποστολή μέσω του Gmail API, με το Google account του χρήστη.
 *
 * Το πλεονέκτημα έναντι του SMTP: **κανένα app password**. Ο χρήστης δίνει μία
 * φορά έγκριση στο consent window της Google και τελείωσε. Το μήνυμα φεύγει
 * κανονικά από τον λογαριασμό του και μπαίνει στα Απεσταλμένα.
 *
 * Το scope είναι `gmail.send`: επιτρέπει **μόνο** αποστολή — το app δεν μπορεί
 * να διαβάσει ούτε ένα μήνυμα.
 */
object GmailSender {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    suspend fun send(
        accessToken: String,
        message: MailSender.Outgoing,
    ) = withContext(Dispatchers.IO) {
        require(message.to.isNotBlank()) { "Λείπει ο παραλήπτης" }

        val raw = buildRawMessage(message)
        val payload = JSONObject().put("raw", raw)

        val request = Request.Builder()
            .url(SEND_URL)
            .header("Authorization", "Bearer $accessToken")
            .post(payload.toString().toRequestBody(JSON))
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val detail = runCatching {
                    JSONObject(body).getJSONObject("error").getString("message")
                }.getOrNull()
                throw IllegalStateException("Gmail API ${response.code}: ${detail ?: body.take(200)}")
            }
        }
    }

    /**
     * Το Gmail API θέλει ολόκληρο το MIME μήνυμα σε base64url. Το χτίσιμο γίνεται
     * με το JavaMail που έχουμε ήδη — ο αποστολέας συμπληρώνεται από τη Google.
     */
    private fun buildRawMessage(message: MailSender.Outgoing): String {
        val session = Session.getInstance(Properties())
        // Κανένα From: η Gmail συμπληρώνει τη διεύθυνση του λογαριασμού. Αν βάζαμε
        // εμείς κάτι, θα έπρεπε να ξέρουμε τη διεύθυνση — και το gmail.send δεν
        // δίνει δικαίωμα να τη διαβάσουμε.
        val mime = MimeMessage(session).apply {
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(message.to))
            setSubject(message.subject, "UTF-8")
            sentDate = java.util.Date()
        }

        val textPart = MimeBodyPart().apply { setText(message.body, "UTF-8") }
        val multipart = MimeMultipart().apply { addBodyPart(textPart) }

        message.attachment?.let { file ->
            multipart.addBodyPart(
                MimeBodyPart().apply {
                    dataHandler = DataHandler(FileDataSource(file))
                    fileName = message.attachmentName ?: file.name
                },
            )
        }
        mime.setContent(multipart)

        val buffer = ByteArrayOutputStream()
        mime.writeTo(buffer)
        return Base64.encodeToString(
            buffer.toByteArray(),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING,
        )
    }

    private const val SEND_URL = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send"
    private val JSON = "application/json; charset=utf-8".toMediaType()
}
