package gr.prosfora.app.doc

import gr.prosfora.app.data.db.OfferWithDetails
import gr.prosfora.app.util.asMoney
import gr.prosfora.app.util.asNumber
import gr.prosfora.app.util.asOfferDate
import gr.prosfora.app.util.strippedKind
import gr.prosfora.app.util.upperGreek
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Συμπληρώνει το .docx πρότυπο με τα δεδομένα μιας προσφοράς.
 *
 * Δουλεύει απευθείας πάνω στο `word/document.xml`. Αυτό είναι εφικτό επειδή στο
 * συγκεκριμένο πρότυπο κάθε placeholder βρίσκεται ολόκληρο μέσα σε ένα `<w:t>`
 * run — δεν είναι σπασμένο σε κομμάτια, οπότε δεν χρειάζεται normalization.
 *
 * Reference implementation & έλεγχος σε πραγματικά δεδομένα:
 * `migration/render_template.py`.
 */
object DocxTemplate {

    private const val DOCUMENT_ENTRY = "word/document.xml"

    // Στο XML τα < > είναι ήδη escaped, οπότε τα markers ψάχνονται ως &lt;&lt;…&gt;&gt;
    private const val LOOP_END = "&lt;&lt;End&gt;&gt;"

    /**
     * Η αρχή της επανάληψης χώρων. Δέχεται και τη γραφή του παλιού προτύπου
     * (`Related Ανάλυση_Χώρων`, κληρονομιά του AppSheet) και τη σύντομη `Χώροι`
     * του καινούριου, ώστε να παίζει όποιο πρότυπο κι αν έχει ο χρήστης στο Drive.
     */
    private val SPACES_START = Regex(
        "&lt;&lt;Start:\\s*\\[?(?:Related Ανάλυση_Χώρων|Χώροι)\\]?&gt;&gt;",
    )
    private val NOTES_START = Regex("&lt;&lt;Start:\\s*SELECT\\(.*?&gt;&gt;", RegexOption.DOT_MATCHES_ALL)

    /** Παράγραφοι που επαναλαμβάνονται μία φορά ανά γραμμή, χωρίς Start/End. */
    private const val NOTE_LINE = "&lt;&lt;[Παρατηρήσεις]&gt;&gt;"
    private const val PAYMENT_LINE = "&lt;&lt;[Τρόπος Πληρωμής]&gt;&gt;"

    /**
     * Δείκτες «μόνο αν …». Όπου εμφανίζονται, η γραμμή του πίνακα (ή η
     * παράγραφος, αν δεν είναι σε πίνακα) υπάρχει μόνο όταν ισχύει η συνθήκη.
     */
    private const val VAT_ONLY = "&lt;&lt;[Αν ΦΠΑ]&gt;&gt;"
    private const val SCAFFOLDING_ONLY = "&lt;&lt;[Αν Σκαλωσιά]&gt;&gt;"
    private const val PERMIT_ONLY = "&lt;&lt;[Αν Άδεια]&gt;&gt;"

    fun render(templateDocx: ByteArray, details: OfferWithDetails): ByteArray {
        val entries = readZip(templateDocx)
        val document = entries[DOCUMENT_ENTRY]?.toString(Charsets.UTF_8)
            ?: error("Το πρότυπο δεν είναι έγκυρο .docx (λείπει το $DOCUMENT_ENTRY)")
        entries[DOCUMENT_ENTRY] = renderXml(document, details).toByteArray(Charsets.UTF_8)
        return writeZip(entries)
    }

    internal fun renderXml(xml: String, details: OfferWithDetails): String {
        var result = expandSpaceRows(xml, details)
        result = expandNoteBullets(result, details)
        result = repeatParagraph(result, NOTE_LINE, details.notes.map { it.text })
        result = repeatParagraph(result, PAYMENT_LINE, details.paymentLines)
        result = applyConditional(result, SCAFFOLDING_ONLY, details.scaffoldingCost > 0.0)
        result = applyConditional(result, PERMIT_ONLY, details.permitCost > 0.0)
        result = applyConditional(result, VAT_ONLY, details.offer.vatIncluded)
        return fillSimpleFields(result, details)
    }

    /**
     * Όταν ισχύει η συνθήκη φεύγει μόνο ο δείκτης· αλλιώς φεύγει ολόκληρη η γραμμή.
     *
     * Δεν αρκεί να μείνουν κενά τα ποσά: μια άδεια γραμμή «ΦΠΑ 24%» ή
     * «ΣΚΑΛΩΣΙΑ» μέσα στον πίνακα διαβάζεται σαν λάθος. Η αφαίρεση γίνεται στο
     * επίπεδο του `<w:tr>` ώστε να δουλεύει και σε πρότυπο που έγραψε ο χρήστης.
     */
    private fun applyConditional(xml: String, marker: String, keep: Boolean): String {
        if (keep) return xml.replace(marker, "")
        var result = xml
        while (true) {
            val at = result.indexOf(marker)
            if (at < 0) return result
            result = dropBlock(result, at)
        }
    }

    /** Σβήνει τη γραμμή πίνακα που περιέχει τη θέση, ή την παράγραφο εκτός πίνακα. */
    private fun dropBlock(xml: String, index: Int): String {
        val rowOpen = maxOf(xml.lastIndexOf("<w:tr ", index), xml.lastIndexOf("<w:tr>", index))
        val rowClosed = xml.lastIndexOf("</w:tr>", index)
        val tag = if (rowOpen >= 0 && rowOpen > rowClosed) "w:tr" else "w:p"
        val (open, close) = enclosingTag(xml, index, tag)
        return xml.substring(0, open) + xml.substring(close)
    }

    /** Η γραμμή του πίνακα ανάμεσα σε `<<Start:…>>` και `<<End>>` επαναλαμβάνεται ανά χώρο. */
    private fun expandSpaceRows(xml: String, details: OfferWithDetails): String {
        val marker = SPACES_START.find(xml)?.range?.first ?: return xml
        val (rowStart, rowEnd) = enclosingTag(xml, marker, "w:tr")
        val rowTemplate = xml.substring(rowStart, rowEnd)

        val rows = details.spaces.sortedBy { it.position }.joinToString("") { space ->
            SPACES_START.replace(rowTemplate, "")
                .replace(LOOP_END, "")
                .replace("&lt;&lt;[Περιγραφή Χώρου]&gt;&gt;", escape(space.description))
                // Το πρότυπο γράφει την Επιφάνεια χωρίς αγκύλες — δεχόμαστε και τις δύο γραφές
                .replace("&lt;&lt;[Επιφάνεια (τ.μ.)]&gt;&gt;", escape(space.area.asNumber()))
                .replace("&lt;&lt;Επιφάνεια (τ.μ.)&gt;&gt;", escape(space.area.asNumber()))
                .replace("&lt;&lt;[Τιμή Μονάδος]&gt;&gt;", escape(space.unitPrice.asMoney()))
                .replace("&lt;&lt;[Σύνολο Γραμμής]&gt;&gt;", escape(space.lineTotal.asMoney()))
        }
        return xml.substring(0, rowStart) + rows + xml.substring(rowEnd)
    }

    /**
     * Οι τρεις παράγραφοι `<<Start:SELECT(…)>>` / `• <<[Κείμενο]>>` / `<<End>>`
     * αντικαθίστανται από μία παράγραφο ανά σημείωση.
     */
    private fun expandNoteBullets(xml: String, details: OfferWithDetails): String {
        val match = NOTES_START.find(xml) ?: return xml
        val (startOpen, startClose) = enclosingTag(xml, match.range.first, "w:p")
        val (bodyOpen, bodyClose) = nextTag(xml, startClose, "w:p") ?: return xml
        val bodyTemplate = xml.substring(bodyOpen, bodyClose)

        val endMarker = xml.indexOf(LOOP_END, bodyClose)
        if (endMarker < 0) return xml
        val (_, endClose) = enclosingTag(xml, endMarker, "w:p")

        val bullets = details.notes.sortedBy { it.position }.joinToString("") { note ->
            bodyTemplate.replace("&lt;&lt;[Κείμενο]&gt;&gt;", escape(note.text))
        }
        return xml.substring(0, startOpen) + bullets + xml.substring(endClose)
    }

    /**
     * Η παράγραφος που περιέχει το [marker] επαναλαμβάνεται μία φορά ανά γραμμή.
     *
     * Αντικαθιστά τη ροή Start/σώμα/End: στο πρότυπο μένει μία μόνο γραμμή, ενώ
     * η μορφοποίησή της (κουκκίδα, στοίχιση, γραμματοσειρά) αντιγράφεται
     * αυτούσια σε κάθε επανάληψη. Χωρίς γραμμές, η παράγραφος φεύγει εντελώς
     * αντί να μείνει κενή και να τρώει χώρο στη σελίδα.
     */
    private fun repeatParagraph(xml: String, marker: String, lines: List<String>): String {
        val at = xml.indexOf(marker)
        if (at < 0) return xml
        val (open, close) = enclosingTag(xml, at, "w:p")
        val template = xml.substring(open, close)
        val expanded = lines.joinToString("") { line -> template.replace(marker, escape(line)) }
        return xml.substring(0, open) + expanded + xml.substring(close)
    }

    private fun fillSimpleFields(xml: String, details: OfferWithDetails): String {
        val offer = details.offer
        // Το «γενικό σύνολο» είναι ό,τι πληρώνει ο πελάτης: με ΦΠΑ όταν υπάρχει
        val total = details.grandTotal.asMoney()
        return xml
            // Το «Χρωματισμός» φεύγει: ο τίτλος του εγγράφου λέει ήδη
            // «ΠΡΟΣΦΟΡΑ ΕΛΑΙΟΧΡΩΜΑΤΙΣΜΩΝ ΓΙΑ …»
            .replace("&lt;&lt;[Είδος]&gt;&gt;", escape(offer.kind.strippedKind()))
            .replace("&lt;&lt;[Οδός / Περιοχή]&gt;&gt;", escape(offer.address.upperGreek()))
            .replace("&lt;&lt;[Ημερομηνία]&gt;&gt;", escape(offer.dateEpochDay.asOfferDate()))
            // Χωρίς ημερομηνία λήξης η φράση «ισχύει έως …» δεν έχει νόημα, οπότε
            // μπαίνει παύλα αντί για κενό που θα έμοιαζε με λάθος του προτύπου.
            .replace(
                "&lt;&lt;[Ισχύει έως]&gt;&gt;",
                escape(offer.validUntilDay?.asOfferDate() ?: "—"),
            )
            .replace("&lt;&lt;[Καθαρή Αξία]&gt;&gt;", escape(details.total.asMoney()))
            .replace("&lt;&lt;[Σκαλωσιά]&gt;&gt;", escape(details.scaffoldingCost.asMoney()))
            .replace("&lt;&lt;[Άδεια]&gt;&gt;", escape(details.permitCost.asMoney()))
            .replace("&lt;&lt;[ΦΠΑ]&gt;&gt;", escape(details.vatAmount.asMoney()))
            .replace("&lt;&lt;[Γενικό Σύνολο Live]&gt;&gt;", escape(total))
            .replace("&lt;&lt;[Γενικό Σύνολο]&gt;&gt;", escape(total))
    }

    /** Τα όρια του `<tag>…</tag>` που περιέχει τη θέση [index]. */
    private fun enclosingTag(xml: String, index: Int, tag: String): Pair<Int, Int> {
        val open = maxOf(xml.lastIndexOf("<$tag ", index), xml.lastIndexOf("<$tag>", index))
        require(open >= 0) { "δεν βρέθηκε άνοιγμα <$tag> πριν τη θέση $index" }
        val close = xml.indexOf("</$tag>", index)
        require(close >= 0) { "δεν βρέθηκε κλείσιμο </$tag> μετά τη θέση $index" }
        return open to close + tag.length + 3
    }

    /**
     * Τα όρια του **επόμενου** `<tag>` μετά τη θέση [index].
     * Ξεχωριστό από το [enclosingTag]: ψάχνοντας προς τα πίσω από θέση που είναι
     * ήδη μετά το κλείσιμο μιας παραγράφου, θα ξαναβρίσκαμε την ίδια παράγραφο.
     */
    private fun nextTag(xml: String, index: Int, tag: String): Pair<Int, Int>? {
        val open = Regex("<$tag[ >]").find(xml, index)?.range?.first ?: return null
        val close = xml.indexOf("</$tag>", open)
        if (close < 0) return null
        return open to close + tag.length + 3
    }

    /** Οι διευθύνσεις και τα κείμενα σημειώσεων μπορεί να έχουν & ή < — πρέπει να γίνουν escape. */
    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    // ------------------------------------------------ επεξεργασία κειμένου ---

    /**
     * Μία επεξεργάσιμη παράγραφος του προτύπου.
     *
     * [hasPlaceholder] σημαίνει ότι το κείμενο περιέχει πεδίο ή δείκτη
     * επανάληψης: τέτοιες παραγράφους μπορεί να τις αλλάξει ο χρήστης, αλλά αν
     * σβήσει τα `<<…>>` το PDF θα βγει με κενά, οπότε προειδοποιείται.
     */
    data class Paragraph(
        val index: Int,
        val text: String,
        val hasPlaceholder: Boolean,
    )

    private val PARAGRAPH = Regex("<w:p[ >].*?</w:p>", RegexOption.DOT_MATCHES_ALL)
    private val TEXT_RUN = Regex("(<w:t[^>]*>)([^<]*)(</w:t>)")

    /** Το κείμενο κάθε μη κενής παραγράφου, με τη σειρά που εμφανίζεται. */
    fun extractParagraphs(templateDocx: ByteArray): List<Paragraph> {
        val xml = documentXml(templateDocx)
        var index = -1
        return PARAGRAPH.findAll(xml).mapNotNull { match ->
            val text = TEXT_RUN.findAll(match.value)
                .joinToString("") { it.groupValues[2] }
                .unescapeXml()
            if (text.isBlank()) return@mapNotNull null
            index++
            Paragraph(
                index = index,
                text = text,
                hasPlaceholder = text.contains("<<") || text.contains(">>"),
            )
        }.toList()
    }

    /**
     * Γράφει πίσω τα αλλαγμένα κείμενα.
     *
     * Όλο το κείμενο μπαίνει στο **πρώτο** run της παραγράφου και τα υπόλοιπα
     * αδειάζουν· έτσι διατηρείται η μορφοποίηση του πρώτου run και δεν χάνονται
     * εικόνες ή πίνακες, που ζουν εκτός `<w:t>`.
     */
    fun applyParagraphEdits(templateDocx: ByteArray, edits: Map<Int, String>): ByteArray {
        if (edits.isEmpty()) return templateDocx
        val entries = readZip(templateDocx)
        val xml = documentXml(templateDocx)

        var paragraphIndex = -1
        val rebuilt = PARAGRAPH.replace(xml) { match ->
            val block = match.value
            val current = TEXT_RUN.findAll(block).joinToString("") { it.groupValues[2] }
            if (current.isBlank()) return@replace block
            paragraphIndex++

            val replacement = edits[paragraphIndex] ?: return@replace block
            var first = true
            TEXT_RUN.replace(block) { run ->
                val open = run.groupValues[1]
                val close = run.groupValues[3]
                if (first) {
                    first = false
                    // Το xml:space="preserve" κρατάει τα κενά στην αρχή και το τέλος
                    val tag = if (open.contains("xml:space")) {
                        open
                    } else {
                        open.dropLast(1) + " xml:space=\"preserve\">"
                    }
                    tag + escape(replacement) + close
                } else {
                    open + close
                }
            }
        }

        entries[DOCUMENT_ENTRY] = rebuilt.toByteArray(Charsets.UTF_8)
        return writeZip(entries)
    }

    private fun documentXml(docx: ByteArray): String =
        readZip(docx)[DOCUMENT_ENTRY]?.toString(Charsets.UTF_8)
            ?: error("Το πρότυπο δεν είναι έγκυρο .docx (λείπει το $DOCUMENT_ENTRY)")

    private fun String.unescapeXml(): String = this
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&amp;", "&")

    private fun readZip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun writeZip(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, payload) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(payload)
                zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
