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

object DocxTemplate {
    private const val DOCUMENT_ENTRY = "word/document.xml"
    private const val LOOP_END = "&lt;&lt;End&gt;&gt;"
    private val SPACES_START = Regex("&lt;&lt;Start:\\s*\\[?(?:Related Ανάλυση_Χώρων|Χώροι)\\]?&gt;&gt;")
    private val NOTES_START = Regex("&lt;&lt;Start:\\s*SELECT\\(.*?&gt;&gt;", RegexOption.DOT_MATCHES_ALL)
    private const val NOTE_LINE = "&lt;&lt;[Παρατηρήσεις]&gt;&gt;"
    private const val PAYMENT_LINE = "&lt;&lt;[Τρόπος Πληρωμής]&gt;&gt;"
    private const val VAT_ONLY = "&lt;&lt;[Αν ΦΠΑ]&gt;&gt;"
    private const val SCAFFOLDING_ONLY = "&lt;&lt;[Αν Σκαλωσιά]&gt;&gt;"
    private const val RUN_CLOSE = "</w:r>"

    /** Ανοίγματα run· δεν πιάνει τα `<w:rPr>`, `<w:rFonts>`, `<w:rStyle>`. */
    private val RUN_OPEN = Regex("""<w:r(?:\s[^>]*)?>""")
    private const val PERMIT_ONLY = "&lt;&lt;[Αν Άδεια]&gt;&gt;"

    fun render(templateDocx: ByteArray, details: OfferWithDetails): ByteArray {
        val entries = readZip(templateDocx)
        val document = entries[DOCUMENT_ENTRY]?.toString(Charsets.UTF_8)
            ?: error("Το πρότυπο δεν είναι έγκυρο .docx (λείπει το $DOCUMENT_ENTRY)")
        entries[DOCUMENT_ENTRY] = renderXml(document, details).toByteArray(Charsets.UTF_8)
        return writeZip(entries)
    }

    /**
     * Το πρότυπο όπως πρέπει να το δει ο χρήστης όταν εγκατασταθεί στο Drive.
     *
     * Ο [render] ξαναχτίζει τα σύνολα κάθε φορά, οπότε το PDF έβγαινε σωστό
     * ακόμη κι από παλιό πρότυπο. Το ίδιο το αρχείο όμως —αυτό που ανοίγει ο
     * χρήστης για να το πειράξει— έμενε χωρίς σκαλωσιά, άδεια, πρόσθετο κόστος
     * και χωρίς γενικό σύνολο. Εδώ γράφεται μία φορά ολόκληρη η διάταξη, με
     * τους δείκτες συνθήκης πάνω στις προαιρετικές γραμμές, ώστε το
     * εγκατεστημένο πρότυπο να μη μπορεί να αποκλίνει από ό,τι τυπώνεται.
     */
    fun withFullTotals(templateDocx: ByteArray): ByteArray {
        val entries = readZip(templateDocx)
        val document = entries[DOCUMENT_ENTRY]?.toString(Charsets.UTF_8) ?: return templateDocx
        entries[DOCUMENT_ENTRY] = fullTotalsLayout(document).toByteArray(Charsets.UTF_8)
        return writeZip(entries)
    }

    internal fun renderXml(xml: String, details: OfferWithDetails): String {
        var result = expandSpaceRows(xml, details)
        result = expandNoteBullets(result, details)
        result = repeatParagraph(result, NOTE_LINE, details.notes.map { it.text })
        result = repeatParagraph(result, PAYMENT_LINE, details.paymentLines)
        result = applyConditional(result, SCAFFOLDING_ONLY, details.scaffoldingCost > 0.0)
        result = applyConditional(result, PERMIT_ONLY, details.permitCost > 0.0)
        result = normalizeTotalsLayout(result, details)
        result = applyConditional(result, VAT_ONLY, details.offer.vatIncluded)
        return fillSimpleFields(result, details)
    }

    private fun applyConditional(xml: String, marker: String, keep: Boolean): String {
        if (keep) return xml.replace(marker, "")
        var result = xml
        while (true) {
            val at = result.indexOf(marker)
            if (at < 0) return result
            result = dropBlock(result, at)
        }
    }

    private fun dropBlock(xml: String, index: Int): String {
        val rowOpen = maxOf(xml.lastIndexOf("<w:tr ", index), xml.lastIndexOf("<w:tr>", index))
        val rowClosed = xml.lastIndexOf("</w:tr>", index)
        val tag = if (rowOpen >= 0 && rowOpen > rowClosed) "w:tr" else "w:p"
        val (open, close) = enclosingTag(xml, index, tag)
        return xml.substring(0, open) + xml.substring(close)
    }

    private fun expandSpaceRows(xml: String, details: OfferWithDetails): String {
        val marker = SPACES_START.find(xml)?.range?.first ?: return xml
        val (rowStart, rowEnd) = enclosingTag(xml, marker, "w:tr")
        val rowTemplate = xml.substring(rowStart, rowEnd)
        val rows = details.spaces.sortedBy { it.position }.joinToString("") { space ->
            SPACES_START.replace(rowTemplate, "")
                .replace(LOOP_END, "")
                .replace("&lt;&lt;[Περιγραφή Χώρου]&gt;&gt;", escape(space.description))
                .replace("&lt;&lt;Επιφάνεια (τ.μ.)&gt;&gt;", escape(space.area.asNumber()))
                .replace("&lt;&lt;[Επιφάνεια (τ.μ.)]&gt;&gt;", escape(space.area.asNumber()))
                .replace("&lt;&lt;[Τιμή Μονάδος]&gt;&gt;", escape(space.unitPrice.asMoney()))
                .replace("&lt;&lt;[Σύνολο Γραμμής]&gt;&gt;", escape(space.lineTotal.asMoney()))
        }
        return xml.substring(0, rowStart) + rows + xml.substring(rowEnd)
    }

    private fun expandNoteBullets(xml: String, details: OfferWithDetails): String {
        val match = NOTES_START.find(xml) ?: return xml
        val (startOpen, startClose) = enclosingTag(xml, match.range.first, "w:p")
        val (bodyOpen, bodyClose) = nextTag(xml, startClose, "w:p") ?: return xml
        val bodyTemplate = xml.substring(bodyOpen, bodyClose)
        val endMarker = xml.indexOf(LOOP_END, bodyClose)
        if (endMarker < 0) return xml
        val (_, endClose) = enclosingTag(xml, endMarker, "w:p")
        val bullets = details.notes.sortedBy { it.position }.joinToString("") { note -> bodyTemplate.replace("&lt;&lt;[Κείμενο]&gt;&gt;", escape(note.text)) }
        return xml.substring(0, startOpen) + bullets + xml.substring(endClose)
    }

    private fun repeatParagraph(xml: String, marker: String, lines: List<String>): String {
        val at = xml.indexOf(marker)
        if (at < 0) return xml
        val (open, close) = enclosingTag(xml, at, "w:p")
        val template = xml.substring(open, close)
        val expanded = lines.joinToString("") { line -> template.replace(marker, escape(line)) }
        return xml.substring(0, open) + expanded + xml.substring(close)
    }

    /**
     * The totals area is rebuilt from one clean row template. Any old totals,
     * extra-cost or VAT rows are removed first, so their original template
     * position can never change the final order.
     *
     * Final order:
     *   ΣΥΝΟΛΟ ΧΩΡΩΝ
     *   πρόσθετα κόστη
     *   ΦΠΑ 24% (only when enabled)
     *   ΓΕΝΙΚΟ ΣΥΝΟΛΟ
     */
    private fun normalizeTotalsLayout(xml: String, details: OfferWithDetails): String {
        val baseMarker = listOf(
            "&lt;&lt;[Καθαρή Αξία]&gt;&gt;",
            "&lt;&lt;[Σύνολο Χώρων]&gt;&gt;",
            "&lt;&lt;[Σύνολο]&gt;&gt;",
        ).mapNotNull { marker -> xml.indexOf(marker).takeIf { it >= 0 } }.minOrNull() ?: return xml

        val (baseOpen, baseClose) = enclosingTag(xml, baseMarker, "w:tr")
        var baseRow = xml.substring(baseOpen, baseClose)
            .replace("&lt;&lt;[Καθαρή Αξία]&gt;&gt;", "&lt;&lt;[Σύνολο Χώρων]&gt;&gt;")
            .replace("&lt;&lt;[Σύνολο]&gt;&gt;", "&lt;&lt;[Σύνολο Χώρων]&gt;&gt;")
            .replaceFirst("ΚΑΘΑΡΗ ΑΞΙΑ</w:t>", "ΣΥΝΟΛΟ ΧΩΡΩΝ</w:t>")
            .replaceFirst("Καθαρή Αξία</w:t>", "Σύνολο Χώρων</w:t>")
            .replaceFirst("ΣΥΝΟΛΟ</w:t>", "ΣΥΝΟΛΟ ΧΩΡΩΝ</w:t>")
            .replaceFirst("Σύνολο</w:t>", "Σύνολο Χώρων</w:t>")

        val regularBaseRow = baseRow
        val boldSpacesRow = boldRow(baseRow)

        // Leave a stable anchor exactly where the first totals row was.
        val anchor = "__PROSFORA_TOTALS_ANCHOR__"
        var result = xml.substring(0, baseOpen) + anchor + xml.substring(baseClose)

        // Remove every old totals/extra/VAT row. This is what guarantees the
        // requested order even when an uploaded template already has rows in a
        // different order or contains old optional markers.
        val removableMarkers = listOf(
            "&lt;&lt;[Σύνολο Χώρων]&gt;&gt;",
            "&lt;&lt;[Σύνολο]&gt;&gt;",
            "&lt;&lt;[Σκαλωσιά]&gt;&gt;",
            "&lt;&lt;[Άδεια]&gt;&gt;",
            "&lt;&lt;[Πρόσθετο Κόστος]&gt;&gt;",
            "&lt;&lt;[ΦΠΑ]&gt;&gt;",
            "&lt;&lt;[Γενικό Σύνολο Live]&gt;&gt;",
            "&lt;&lt;[Γενικό Σύνολο]&gt;&gt;",
        )
        removableMarkers.forEach { marker ->
            while (true) {
                val at = result.indexOf(marker)
                if (at < 0) break
                result = dropBlock(result, at)
            }
        }

        fun cloneRow(label: String, marker: String, bold: Boolean = false): String {
            val row = regularBaseRow
                .replace("&lt;&lt;[Σύνολο Χώρων]&gt;&gt;", "&lt;&lt;[$marker]&gt;&gt;")
                .replaceFirst("ΣΥΝΟΛΟ ΧΩΡΩΝ</w:t>", "$label</w:t>")
                .replaceFirst("Σύνολο Χώρων</w:t>", "$label</w:t>")
            return if (bold) boldRow(row) else row
        }

        val totalsRows = buildString {
            append(boldSpacesRow)

            if (details.scaffoldingCost > 0.0) {
                append(cloneRow("ΣΚΑΛΩΣΙΑ", "Σκαλωσιά"))
            }
            if (details.permitCost > 0.0) {
                append(cloneRow("ΑΔΕΙΑ ΜΙΚΡΗΣ ΚΛΙΜΑΚΑΣ", "Άδεια"))
            }
            if (details.customExtraCost > 0.0 && details.offer.customExtraName.isNotBlank()) {
                append(cloneRow(details.offer.customExtraName.trim().uppercase(), "Πρόσθετο Κόστος"))
            }
            if (details.offer.vatIncluded) {
                append(cloneRow("ΦΠΑ 24%", "ΦΠΑ"))
            }

            append(cloneRow("ΓΕΝΙΚΟ ΣΥΝΟΛΟ", "Γενικό Σύνολο Live", bold = true))
        }

        return result.replace(anchor, totalsRows)
    }

    /**
     * Η ίδια σειρά με τον [normalizeTotalsLayout], αλλά με **όλες** τις γραμμές
     * παρούσες: οι προαιρετικές φέρουν τον δείκτη συνθήκης τους και σβήνονται
     * κατά την εκτύπωση όταν δεν ισχύουν.
     */
    internal fun fullTotalsLayout(xml: String): String {
        val baseMarker = listOf(
            "&lt;&lt;[Καθαρή Αξία]&gt;&gt;",
            "&lt;&lt;[Σύνολο Χώρων]&gt;&gt;",
            "&lt;&lt;[Σύνολο]&gt;&gt;",
        ).mapNotNull { marker -> xml.indexOf(marker).takeIf { it >= 0 } }.minOrNull() ?: return xml

        val (baseOpen, baseClose) = enclosingTag(xml, baseMarker, "w:tr")
        val baseRow = xml.substring(baseOpen, baseClose)
            .replace("&lt;&lt;[Καθαρή Αξία]&gt;&gt;", "&lt;&lt;[Σύνολο Χώρων]&gt;&gt;")
            .replace("&lt;&lt;[Σύνολο]&gt;&gt;", "&lt;&lt;[Σύνολο Χώρων]&gt;&gt;")
            .replaceFirst("ΚΑΘΑΡΗ ΑΞΙΑ</w:t>", "ΣΥΝΟΛΟ ΧΩΡΩΝ</w:t>")
            .replaceFirst("Καθαρή Αξία</w:t>", "Σύνολο Χώρων</w:t>")
            .replaceFirst("ΣΥΝΟΛΟ</w:t>", "ΣΥΝΟΛΟ ΧΩΡΩΝ</w:t>")
            .replaceFirst("Σύνολο</w:t>", "Σύνολο Χώρων</w:t>")

        val anchor = "__PROSFORA_TEMPLATE_TOTALS__"
        var result = xml.substring(0, baseOpen) + anchor + xml.substring(baseClose)
        listOf(
            "&lt;&lt;[Σύνολο Χώρων]&gt;&gt;",
            "&lt;&lt;[Σύνολο]&gt;&gt;",
            "&lt;&lt;[Σκαλωσιά]&gt;&gt;",
            "&lt;&lt;[Άδεια]&gt;&gt;",
            "&lt;&lt;[Πρόσθετο Κόστος]&gt;&gt;",
            "&lt;&lt;[ΦΠΑ]&gt;&gt;",
            "&lt;&lt;[Γενικό Σύνολο Live]&gt;&gt;",
            "&lt;&lt;[Γενικό Σύνολο]&gt;&gt;",
        ).forEach { marker ->
            while (true) {
                val at = result.indexOf(marker)
                if (at < 0) break
                result = dropBlock(result, at)
            }
        }

        fun row(label: String, marker: String, guard: String = "", bold: Boolean = false): String {
            val built = baseRow
                .replace("&lt;&lt;[Σύνολο Χώρων]&gt;&gt;", "&lt;&lt;[$marker]&gt;&gt;")
                .replaceFirst("ΣΥΝΟΛΟ ΧΩΡΩΝ</w:t>", "$guard$label</w:t>")
                .replaceFirst("Σύνολο Χώρων</w:t>", "$guard$label</w:t>")
            return if (bold) boldRow(built) else built
        }

        val block = buildString {
            append(boldRow(baseRow))
            append(row("ΣΚΑΛΩΣΙΑ", "Σκαλωσιά", SCAFFOLDING_ONLY))
            append(row("ΑΔΕΙΑ ΜΙΚΡΗΣ ΚΛΙΜΑΚΑΣ", "Άδεια", PERMIT_ONLY))
            append(row("ΠΡΟΣΘΕΤΟ ΚΟΣΤΟΣ", "Πρόσθετο Κόστος"))
            append(row("ΦΠΑ 24%", "ΦΠΑ", VAT_ONLY))
            append(row("ΓΕΝΙΚΟ ΣΥΝΟΛΟ", "Γενικό Σύνολο Live", bold = true))
        }
        return result.replace(anchor, block)
    }

    /**
     * Κάνει έντονη **κάθε** λέξη της γραμμής, ό,τι μορφοποίηση κι αν είχε το
     * πρότυπο.
     *
     * Η προηγούμενη εκδοχή απέτυχε με τρεις τρόπους ταυτόχρονα: έπιανε μόνο το
     * πρώτο run —δηλαδή την ετικέτα, ποτέ το ποσό δίπλα της— σταματούσε αν
     * έβρισκε `<w:b/>` οπουδήποτε στη γραμμή, και έβαζε το `<w:b/>` αμέσως μετά
     * το `<w:rPr>`. Το τελευταίο είναι που το έκανε να μη φαίνεται καθόλου: το
     * OOXML ορίζει **σειρά** για τα παιδιά του `<w:rPr>`, και το `<w:b/>`
     * πρέπει να έρθει μετά τα `<w:rStyle>` και `<w:rFonts>`. Εκτός σειράς, ο
     * επεξεργαστής το πετάει.
     */
    internal fun boldRow(row: String): String {
        val out = StringBuilder()
        var at = 0
        while (true) {
            val open = RUN_OPEN.find(row, at) ?: break
            val bodyStart = open.range.last + 1
            val close = row.indexOf(RUN_CLOSE, bodyStart)
            if (close < 0) break
            out.append(row, at, bodyStart)
            out.append(boldRun(row.substring(bodyStart, close)))
            at = close
        }
        out.append(row, at, row.length)
        return out.toString()
    }

    /** Το περιεχόμενο ενός `<w:r>`, με εξασφαλισμένο `<w:b/>`. */
    private fun boldRun(body: String): String {
        // Runs χωρίς κείμενο —εικόνες, διαστήματα σελιδοποίησης— μένουν ως έχουν
        if (!body.contains("<w:t")) return body

        val propsOpen = body.indexOf("<w:rPr>")
        if (propsOpen < 0) {
            val empty = body.indexOf("<w:rPr/>")
            if (empty >= 0) return body.replaceFirst("<w:rPr/>", "<w:rPr><w:b/></w:rPr>")
            // Το <w:rPr> πρέπει να είναι το πρώτο παιδί του run
            return "<w:rPr><w:b/></w:rPr>$body"
        }

        val propsClose = body.indexOf("</w:rPr>", propsOpen)
        if (propsClose < 0) return body
        val inner = body.substring(propsOpen + "<w:rPr>".length, propsClose)
        if (inner.contains("<w:b/>") || inner.contains("<w:b ")) return body

        // Μετά τα rStyle/rFonts, όπως ορίζει η σειρά του CT_RPr
        var insertAt = 0
        listOf("<w:rStyle", "<w:rFonts").forEach { tag ->
            val start = inner.indexOf(tag)
            if (start >= 0) {
                val end = inner.indexOf('>', start)
                if (end >= 0) insertAt = maxOf(insertAt, end + 1)
            }
        }
        val patched = inner.substring(0, insertAt) + "<w:b/>" + inner.substring(insertAt)
        return body.substring(0, propsOpen + "<w:rPr>".length) + patched + body.substring(propsClose)
    }

    private fun fillSimpleFields(xml: String, details: OfferWithDetails): String {
        val offer = details.offer
        val total = details.grandTotal.asMoney()
        // The custom-extra row has already received the custom name as its
        // label. Its numeric cell must therefore contain ONLY the amount.
        val customAmount = details.customExtraCost.asMoney()
        return xml
            .replace("&lt;&lt;[Είδος]&gt;&gt;", escape(offer.kind.strippedKind()))
            .replace("&lt;&lt;[Οδός / Περιοχή]&gt;&gt;", escape(offer.address.upperGreek()))
            .replace("&lt;&lt;[Ημερομηνία]&gt;&gt;", escape(offer.dateEpochDay.asOfferDate()))
            .replace("&lt;&lt;[Ισχύει έως]&gt;&gt;", escape(offer.validUntilDay?.asOfferDate() ?: "—"))
            .replace("&lt;&lt;[Καθαρή Αξία]&gt;&gt;", escape(details.linesTotal.asMoney()))
            .replace("&lt;&lt;[Σύνολο Χώρων]&gt;&gt;", escape(details.linesTotal.asMoney()))
            .replace("&lt;&lt;[Σύνολο]&gt;&gt;", escape(details.total.asMoney()))
            .replace("&lt;&lt;[Πρόσθετο Κόστος]&gt;&gt;", escape(customAmount))
            .replace("&lt;&lt;[Σκαλωσιά]&gt;&gt;", escape(details.scaffoldingCost.asMoney()))
            .replace("&lt;&lt;[Άδεια]&gt;&gt;", escape(details.permitCost.asMoney()))
            .replace("&lt;&lt;[ΦΠΑ]&gt;&gt;", escape(details.vatAmount.asMoney()))
            .replace("&lt;&lt;[Γενικό Σύνολο Live]&gt;&gt;", escape(total))
            .replace("&lt;&lt;[Γενικό Σύνολο]&gt;&gt;", escape(total))
    }

    private fun enclosingTag(xml: String, index: Int, tag: String): Pair<Int, Int> {
        val open = maxOf(xml.lastIndexOf("<$tag ", index), xml.lastIndexOf("<$tag>", index))
        require(open >= 0) { "δεν βρέθηκε άνοιγμα <$tag πριν τη θέση $index" }
        val close = xml.indexOf("</$tag>", index)
        require(close >= 0) { "δεν βρέθηκε κλείσιμο </$tag> μετά τη θέση $index" }
        return open to close + tag.length + 3
    }

    private fun nextTag(xml: String, index: Int, tag: String): Pair<Int, Int>? {
        val open = Regex("<$tag[ >]").find(xml, index)?.range?.first ?: return null
        val close = xml.indexOf("</$tag>", open)
        if (close < 0) return null
        return open to close + tag.length + 3
    }

    private fun escape(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    data class Paragraph(val index: Int, val text: String, val hasPlaceholder: Boolean)
    private val PARAGRAPH = Regex("<w:p[ >].*?</w:p>", RegexOption.DOT_MATCHES_ALL)
    private val TEXT_RUN = Regex("(<w:t[^>]*>)([^<]*)(</w:t>)")

    fun extractParagraphs(templateDocx: ByteArray): List<Paragraph> {
        val xml = documentXml(templateDocx)
        var index = -1
        return PARAGRAPH.findAll(xml).mapNotNull { match ->
            val text = TEXT_RUN.findAll(match.value).joinToString("") { it.groupValues[2] }.unescapeXml()
            if (text.isBlank()) return@mapNotNull null
            index++
            Paragraph(index, text, text.contains("<<") || text.contains(">>"))
        }.toList()
    }

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
                    val tag = if (open.contains("xml:space")) open else open.dropLast(1) + " xml:space=\"preserve\">"
                    tag + escape(replacement) + close
                } else open + close
            }
        }
        entries[DOCUMENT_ENTRY] = rebuilt.toByteArray(Charsets.UTF_8)
        return writeZip(entries)
    }

    private fun documentXml(docx: ByteArray): String = readZip(docx)[DOCUMENT_ENTRY]?.toString(Charsets.UTF_8)
        ?: error("Το πρότυπο δεν είναι έγκυρο .docx (λείπει το $DOCUMENT_ENTRY)")

    private fun String.unescapeXml(): String = this.replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    private fun readZip(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val entries = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                zip.closeEntry(); entry = zip.nextEntry
            }
        }
        return entries
    }

    private fun writeZip(entries: Map<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, payload) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(payload); zip.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
