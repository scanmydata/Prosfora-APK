package gr.prosfora.app.debt

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Τι είναι το αρχείο που έδωσε ο χρήστης, και αν κρύβει κείμενο μέσα του.
 *
 * Δεν αποκωδικοποιεί PDF — μόνο κοιτάζει. Η ερώτηση που απαντά είναι μία: αξίζει
 * να ζητήσουμε **εξαγωγή κειμένου**, ή το έγγραφο είναι εικόνα και χρειάζεται
 * OCR; Η διαφορά μετράει: η εξαγωγή δίνει τα ποσά αυτούσια, ενώ το OCR μπορεί
 * να διαβάσει 8 αντί για 0.
 */
object DocumentBytes {

    enum class Kind(val mime: String, val ocrType: String) {
        PDF("application/pdf", "PDF"),
        PNG("image/png", "PNG"),
        JPEG("image/jpeg", "JPG"),
        ;

        val isImage: Boolean get() = this != PDF
    }

    fun kindOf(bytes: ByteArray): Kind? = when {
        bytes.size < 8 -> null
        bytes.startsWith("%PDF") -> Kind.PDF
        // \x89PNG
        bytes[0] == 0x89.toByte() && bytes.startsWith("PNG", from = 1) -> Kind.PNG
        bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> Kind.JPEG
        else -> null
    }

    /**
     * Έχει το PDF επίπεδο κειμένου;
     *
     * Το κριτήριο είναι η ύπαρξη γραμματοσειράς: ένα PDF που γράφει κείμενο
     * δηλώνει `/Font` στους πόρους της σελίδας. Το «Σημείωμα για Πληρωμή» της
     * ΑΑΔΕ δεν έχει καμία — είναι σχεδιασμένο σε καμπύλες, χωρίς ούτε έναν
     * χαρακτήρα μέσα του.
     *
     * Στα νεότερα PDF το δέντρο σελίδων ζει μέσα σε συμπιεσμένα object streams,
     * γι' αυτό ψάχνουμε και εκεί. Ό,τι δεν ξεσυμπιέζεται προσπερνιέται σιωπηλά:
     * η απάντηση είναι υπόδειξη δρόμου, όχι ετυμηγορία.
     */
    fun hasTextLayer(bytes: ByteArray): Boolean {
        if (kindOf(bytes) != Kind.PDF) return false
        if (bytes.contains(FONT)) return true
        return inflatedStreams(bytes).any { it.contains(FONT) }
    }

    private val FONT = "/Font".toByteArray(Charsets.US_ASCII)
    private val STREAM = "stream".toByteArray(Charsets.US_ASCII)
    private val ENDSTREAM = "endstream".toByteArray(Charsets.US_ASCII)

    /** Το πολύ [limit] streams — αρκούν για να βρεθεί γραμματοσειρά αν υπάρχει. */
    private fun inflatedStreams(bytes: ByteArray, limit: Int = 40): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var index = 0
        while (out.size < limit) {
            val start = bytes.indexOf(STREAM, index)
            if (start < 0) break
            var from = start + STREAM.size
            if (from < bytes.size && bytes[from] == '\r'.code.toByte()) from++
            if (from < bytes.size && bytes[from] == '\n'.code.toByte()) from++
            val end = bytes.indexOf(ENDSTREAM, from)
            if (end < 0) break
            index = end + ENDSTREAM.size
            inflate(bytes, from, end)?.let { out += it }
        }
        return out
    }

    private fun inflate(bytes: ByteArray, from: Int, to: Int): ByteArray? = runCatching {
        val inflater = Inflater()
        inflater.setInput(bytes, from, to - from)
        val buffer = ByteArray(8 * 1024)
        val sink = ByteArrayOutputStream()
        while (!inflater.finished() && sink.size() < 512 * 1024) {
            val read = inflater.inflate(buffer)
            if (read == 0) break
            sink.write(buffer, 0, read)
        }
        inflater.end()
        sink.toByteArray().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private fun ByteArray.startsWith(text: String, from: Int = 0): Boolean {
        if (size < from + text.length) return false
        return text.indices.all { this[from + it] == text[it].code.toByte() }
    }

    private fun ByteArray.contains(needle: ByteArray): Boolean = indexOf(needle, 0) >= 0

    private fun ByteArray.indexOf(needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || size < needle.size) return -1
        outer@ for (i in from..size - needle.size) {
            for (j in needle.indices) if (this[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
