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

    private fun normalizeTotalsLayout(xml: String, details: OfferWithDetails): String {
        val baseMarker = listOf("&lt;&lt;[Καθαρή Αξία]&gt;&gt;", "&lt;&lt;[Σύνολο Χώρων]&gt;&gt;", "&lt;&lt;[Σύνολο]&gt;&gt;")
            .mapNotNull { marker -> xml.indexOf(marker).takeIf { it >= 0 } }.minOrNull() ?: return xml

        val (baseOpen, baseClose) = enclosingTag(xml, baseMarker, "w:tr")
        var baseRow = xml.substring(baseOpen, baseClose)
            .replace("&lt;&lt;[Καθαρή Αξία]&gt;&gt;", "&lt;&lt;[Σύνολο Χώρων]&gt;&gt;")
            .replace("&lt;&lt;[Σύνολο]&gt;&gt;", "&lt;&lt;[Σύνολο Χώρων]&gt;&gt;")
            .replaceFirst("ΚΑΘΑΡΗ ΑΞΙΑ</w:t>", "ΣΥΝΟΛΟ ΧΩΡΩΝ</w:t>")
            .replaceFirst("Καθαρή Αξία</w:t>", "Σύνολο Χώρων</w:t>")
            .replaceFirst("ΣΥΝΟΛΟ</w:t>", "ΣΥΝΟΛΟ ΧΩΡΩΝ</w:t>")
            .replaceFirst("Σύνολο</w:t>", "Σύνολο Χώρων</w:t>")

        baseRow = boldRow(baseRow)
        var result = xml.substring(0, baseOpen) + baseRow + xml.substring(baseClose)

        fun cloneRow(label: String, marker: String, bold: Boolean = false): String {
            var row = baseRow
                .replace("&lt;&lt;[Σύνολο Χώρων]&gt;&gt;", "&lt;&lt;[$marker]&gt;&gt;")
                .replaceFirst("ΣΥΝΟΛΟ ΧΩΡΩΝ</w:t>", "$label</w:t>")
                .replaceFirst("Σύνολο Χώρων</w:t>", "$label</w:t>")
            return if (bold) boldRow(row) else row
        }

        val additions = buildString {
            if (details.scaffoldingCost > 0.0 && !result.contains("&lt;&lt;[Σκαλωσιά]&gt;&gt;")) {
                append(cloneRow("ΣΚΑΛΩΣΙΑ", "Σκαλωσιά"))
            }
            if (details.permitCost > 0.0 && !result.contains("&lt;&lt;[Άδεια]&gt;&gt;")) {
                append(cloneRow("ΑΔΕΙΑ ΜΙΚΡΗΣ ΚΛΙΜΑΚΑΣ", "Άδεια"))
            }
            if (details.customExtraCost > 0.0 && details.offer.customExtraName.isNotBlank() && !result.contains("&lt;&lt;[Πρόσθετο Κόστος]&gt;&gt;")) {
                append(cloneRow(details.offer.customExtraName.uppercase(), "Πρόσθετο Κόστος"))
            }
            if (details.offer.vatIncluded && !result.contains("&lt;&lt;[ΦΠΑ]&gt;&gt;")) {
                append(cloneRow("ΦΠΑ 24%", "ΦΠΑ"))
            }
        }

        val grandMarkers = listOf("&lt;&lt;[Γενικό Σύνολο Live]&gt;&gt;", "&lt;&lt;[Γενικό Σύνολο]&gt;&gt;")
        val grandMarker = grandMarkers.mapNotNull { marker -> result.indexOf(marker).takeIf { it >= 0 } }.minOrNull()
        if (additions.isNotEmpty() && grandMarker != null) {
            val (grandOpen, _) = enclosingTag(result, grandMarker, "w:tr")
            result = result.substring(0, grandOpen) + additions + result.substring(grandOpen)
        }

        val currentGrandMarker = grandMarkers.mapNotNull { marker -> result.indexOf(marker).takeIf { it >= 0 } }.minOrNull()
        if (currentGrandMarker != null) {
            val (grandOpen, grandClose) = enclosingTag(result, currentGrandMarker, "w:tr")
            val grandRow = boldRow(
                result.substring(grandOpen, grandClose)
                    .replaceFirst("ΣΥΝΟΛΟ</w:t>", "ΓΕΝΙΚΟ ΣΥΝΟΛΟ</w:t>")
                    .replaceFirst("Σύνολο</w:t>", "Γενικό Σύνολο</w:t>"),
            )
            result = result.substring(0, grandOpen) + grandRow + result.substring(grandClose)
        }
        return result
    }

    /** Bold the generated totals rows without relying on how the source template was formatted. */
    private fun boldRow(row: String): String {
        if (row.contains("<w:b/>") || row.contains("<w:b ")) return row
        return if (row.contains("<w:rPr>")) {
            row.replaceFirst("<w:rPr>", "<w:rPr><w:b/>")
        } else {
            row.replaceFirst("<w:r>", "<w:r><w:rPr><w:b/></w:rPr>")
        }
    }

    private fun fillSimpleFields(xml: String, details: OfferWithDetails): String {
        val offer = details.offer
        val total = details.grandTotal.asMoney()
        val customText = if (offer.customExtraName.isNotBlank()) "${offer.customExtraName}: ${details.customExtraCost.asMoney()}" else ""
        return xml
            .replace("&lt;&lt;[Είδος]&gt;&gt;", escape(offer.kind.strippedKind()))
            .replace("&lt;&lt;[Οδός / Περιοχή]&gt;&gt;", escape(offer.address.upperGreek()))
            .replace("&lt;&lt;[Ημερομηνία]&gt;&gt;", escape(offer.dateEpochDay.asOfferDate()))
            .replace("&lt;&lt;[Ισχύει έως]&gt;&gt;", escape(offer.validUntilDay?.asOfferDate() ?: "—"))
            .replace("&lt;&lt;[Καθαρή Αξία]&gt;&gt;", escape(details.linesTotal.asMoney()))
            .replace("&lt;&lt;[Σύνολο Χώρων]&gt;&gt;", escape(details.linesTotal.asMoney()))
            .replace("&lt;&lt;[Σύνολο]&gt;&gt;", escape(details.total.asMoney()))
            .replace("&lt;&lt;[Πρόσθετο Κόστος]&gt;&gt;", escape(customText.ifBlank { (details.scaffoldingCost + details.permitCost).asMoney() }))
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
