package gr.prosfora.app.doc

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Ετοιμάζει ένα .docx που έφερε ο χρήστης, ώστε να βγει σωστά ως PDF.
 *
 * Το Word δεν επιβάλλει τίποτα: ένα έγγραφο μπορεί να είναι Letter με περιθώρια
 * τριών εκατοστών ή μισού, και συχνά είναι — ειδικά όσα ξεκίνησαν από πρότυπο
 * αμερικανικών ρυθμίσεων. Επειδή το αποτέλεσμα τυπώνεται και στέλνεται ως A4,
 * εδώ επιβάλλονται μέγεθος σελίδας, λογικά περιθώρια και αρίθμηση σελίδων.
 *
 * Ό,τι άλλο έφτιαξε ο χρήστης — γραμματοσειρές, πίνακες, εικόνες, στοιχίσεις —
 * μένει ανέγγιχτο.
 */
object DocxPrintLayout {

    /** Περιθώρια σε twips· 1 εκατοστό = 567. */
    private const val MIN_MARGIN = 454   // 0,8 εκ. — κάτω από αυτό κόβουν οι εκτυπωτές
    private const val MAX_MARGIN = 1701  // 3 εκ. — πάνω από αυτό αδειάζει η σελίδα

    private const val A4_WIDTH = 11906
    private const val A4_HEIGHT = 16838

    private const val DOCUMENT = "word/document.xml"
    private const val RELS = "word/_rels/document.xml.rels"
    private const val TYPES = "[Content_Types].xml"
    private const val FOOTER = "word/footerProsfora.xml"
    private const val FOOTER_ID = "rIdProsforaFtr"

    private val PAGE_SIZE = Regex("<w:pgSz[^>]*/>")
    private val PAGE_MARGINS = Regex("<w:pgMar[^>]*/>")
    private val SECTION_OPEN = Regex("<w:sectPr[^>]*>")

    /**
     * Επιστρέφει το έγγραφο έτοιμο για A4. Αν κάτι δεν αναγνωρίζεται, γυρίζει το
     * πρωτότυπο αυτούσιο: καλύτερα ένα πρότυπο με λάθος περιθώρια παρά ένα
     * χαλασμένο αρχείο.
     */
    fun normalize(docx: ByteArray): ByteArray {
        val entries = readZip(docx)
        val document = entries[DOCUMENT]?.toString(Charsets.UTF_8) ?: return docx

        // Το τελευταίο sectPr είναι αυτό του σώματος· τα προηγούμενα ανήκουν σε
        // αλλαγές ενότητας μέσα στο κείμενο και δεν μας αφορούν
        val open = document.lastIndexOf("<w:sectPr")
        if (open < 0) return docx
        val closeAt = document.indexOf("</w:sectPr>", open)
        if (closeAt < 0) return docx
        val close = closeAt + "</w:sectPr>".length

        var section = document.substring(open, close)
        section = withA4(section)
        section = withSaneMargins(section)

        if (!section.contains("w:footerReference")) {
            val reference = """<w:footerReference w:type="default" r:id="$FOOTER_ID"/>"""
            section = SECTION_OPEN.replaceFirst(section) { it.value + reference }

            val rels = entries[RELS]?.toString(Charsets.UTF_8) ?: return docx
            val types = entries[TYPES]?.toString(Charsets.UTF_8) ?: return docx
            entries[FOOTER] = pageNumberFooter().toByteArray(Charsets.UTF_8)
            entries[RELS] = withFooterRelationship(rels).toByteArray(Charsets.UTF_8)
            entries[TYPES] = withFooterContentType(types).toByteArray(Charsets.UTF_8)
        }

        entries[DOCUMENT] = (document.substring(0, open) + section + document.substring(close))
            .toByteArray(Charsets.UTF_8)
        return writeZip(entries)
    }

    private fun withA4(section: String): String {
        val a4 = """<w:pgSz w:w="$A4_WIDTH" w:h="$A4_HEIGHT"/>"""
        val existing = PAGE_SIZE.find(section)
        return when {
            existing != null -> section.replace(existing.value, a4)
            // Η σειρά μέσα στο sectPr μετράει: το pgSz προηγείται του pgMar
            section.contains("<w:pgMar") -> section.replaceFirst("<w:pgMar", a4 + "<w:pgMar")
            else -> section.replace("</w:sectPr>", a4 + "</w:sectPr>")
        }
    }

    private fun withSaneMargins(section: String): String {
        val match = PAGE_MARGINS.find(section)
            ?: return section.replace(
                "</w:sectPr>",
                """<w:pgMar w:top="680" w:right="1020" w:bottom="624" w:left="1020"""" +
                    """ w:header="340" w:footer="340"/></w:sectPr>""",
            )

        var tag = match.value
        listOf("top", "right", "bottom", "left").forEach { edge ->
            // Κάποια αρχεία γράφουν τα περιθώρια με δεκαδικά — το Word τα δέχεται
            val attribute = Regex("""w:$edge="(-?[0-9.]+)"""").find(tag) ?: return@forEach
            val twips = attribute.groupValues[1].toDoubleOrNull()?.toInt() ?: return@forEach
            val clamped = twips.coerceIn(MIN_MARGIN, MAX_MARGIN)
            // Γράφεται πάντα ως ακέραιος, ακόμη κι όταν χωρούσε: τα δεκαδικά
            // twips είναι νόμιμα αλλά μπερδεύουν όποιον διαβάσει το αρχείο μετά
            tag = tag.replace(attribute.value, """w:$edge="$clamped"""")
        }
        return section.replace(match.value, tag)
    }

    private fun withFooterRelationship(rels: String): String =
        if (rels.contains(FOOTER_ID)) {
            rels
        } else {
            rels.replace(
                "</Relationships>",
                """<Relationship Id="$FOOTER_ID" Type="$FOOTER_REL_TYPE"""" +
                    """ Target="footerProsfora.xml"/></Relationships>""",
            )
        }

    private fun withFooterContentType(types: String): String =
        if (types.contains("footerProsfora.xml")) {
            types
        } else {
            types.replace(
                "</Types>",
                """<Override PartName="/word/footerProsfora.xml"""" +
                    """ ContentType="$FOOTER_CONTENT_TYPE"/></Types>""",
            )
        }

    /** «Σελίδα X από Y», δεξιά, με πεδία που υπολογίζει ο επεξεργαστής κειμένου. */
    private fun pageNumberFooter(): String {
        fun run(inner: String) =
            """<w:r><w:rPr><w:sz w:val="16"/><w:color w:val="595959"/></w:rPr>$inner</w:r>"""

        fun text(value: String) = run("""<w:t xml:space="preserve">$value</w:t>""")

        fun field(instruction: String) = buildString {
            append(run("""<w:fldChar w:fldCharType="begin"/>"""))
            append(run("""<w:instrText xml:space="preserve">$instruction</w:instrText>"""))
            append(run("""<w:fldChar w:fldCharType="separate"/>"""))
            append(run("<w:t>1</w:t>"))
            append(run("""<w:fldChar w:fldCharType="end"/>"""))
        }

        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""" +
            """<w:ftr xmlns:w="$WORD_NS">""" +
            """<w:p><w:pPr><w:jc w:val="right"/></w:pPr>""" +
            text("Σελίδα ") + field(" PAGE ") + text(" από ") + field(" NUMPAGES ") +
            "</w:p></w:ftr>"
    }

    private const val WORD_NS =
        "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private const val FOOTER_REL_TYPE =
        "http://schemas.openxmlformats.org/officeDocument/2006/relationships/footer"
    private const val FOOTER_CONTENT_TYPE =
        "application/vnd.openxmlformats-officedocument.wordprocessingml.footer+xml"

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

    /** Ένα .docx είναι ZIP· αν δεν αρχίζει με «PK» δεν είναι έγγραφο του Word. */
    fun looksLikeDocx(bytes: ByteArray): Boolean =
        bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()
}
