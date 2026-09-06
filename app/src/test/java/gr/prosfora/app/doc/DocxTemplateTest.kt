package gr.prosfora.app.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Έλεγχοι για τα έντονα σύνολα του PDF.
 *
 * Το «δεν δουλεύουν τα bold» ήταν τρία σφάλματα μαζί, και το καθένα σπάει
 * διαφορετική γραμμή — γι' αυτό μετριούνται χωριστά.
 */
class DocxTemplateTest {

    /** Δύο κελιά: η ετικέτα και το ποσό. Πρέπει να γίνουν και τα δύο. */
    @Test
    fun `κάθε κελί της γραμμής γίνεται έντονο`() {
        val row = "<w:tr>" +
            "<w:tc><w:p><w:r><w:t>ΣΥΝΟΛΟ</w:t></w:r></w:p></w:tc>" +
            "<w:tc><w:p><w:r><w:t>100,00 €</w:t></w:r></w:p></w:tc>" +
            "</w:tr>"
        assertEquals(2, DocxTemplate.boldRow(row).countOf("<w:b/>"))
    }

    /**
     * Παλιά, ένα `<w:b/>` οπουδήποτε στη γραμμή σταματούσε τα υπόλοιπα κελιά.
     * Έτσι μια ήδη έντονη ετικέτα κρατούσε το ποσό κανονικό.
     */
    @Test
    fun `ένα ήδη έντονο κελί δεν εμποδίζει τα υπόλοιπα`() {
        val row = "<w:tr>" +
            "<w:tc><w:p><w:r><w:rPr><w:b/></w:rPr><w:t>ΣΥΝΟΛΟ</w:t></w:r></w:p></w:tc>" +
            "<w:tc><w:p><w:r><w:t>100,00 €</w:t></w:r></w:p></w:tc>" +
            "</w:tr>"
        assertEquals(2, DocxTemplate.boldRow(row).countOf("<w:b/>"))
    }

    /**
     * Το OOXML ορίζει σειρά για τα παιδιά του `<w:rPr>`. Με το `<w:b/>` πριν
     * από το `<w:rFonts>` ο επεξεργαστής το αγνοεί, και το bold «δεν δούλευε»
     * ενώ ήταν γραμμένο μέσα στο αρχείο.
     */
    @Test
    fun `το έντονο μπαίνει μετά τα rStyle και rFonts`() {
        val row = "<w:tr><w:tc><w:p><w:r>" +
            "<w:rPr><w:rStyle w:val=\"X\"/><w:rFonts w:ascii=\"Arial\"/><w:sz w:val=\"22\"/></w:rPr>" +
            "<w:t>ΓΕΝΙΚΟ ΣΥΝΟΛΟ</w:t></w:r></w:p></w:tc></w:tr>"
        val bolded = DocxTemplate.boldRow(row)
        assertTrue(
            "το <w:b/> πρέπει να ακολουθεί το rFonts",
            bolded.contains("<w:rFonts w:ascii=\"Arial\"/><w:b/>"),
        )
        assertTrue(bolded.indexOf("<w:b/>") < bolded.indexOf("<w:sz"))
    }

    /** Ένα run χωρίς κείμενο δεν έχει τι να κάνει έντονο. */
    @Test
    fun `run χωρίς κείμενο μένει ανέγγιχτο`() {
        val row = "<w:tr><w:tc><w:p><w:r><w:drawing/></w:r></w:p></w:tc></w:tr>"
        assertEquals(row, DocxTemplate.boldRow(row))
    }

    /** Τα `<w:rPr>` δεν είναι runs· δεν πρέπει να μπερδευτούν με `<w:r>`. */
    @Test
    fun `οι ετικέτες rPr και rFonts δεν περνιούνται για run`() {
        val row = "<w:tr><w:tc><w:p><w:pPr><w:rPr><w:i/></w:rPr></w:pPr>" +
            "<w:r><w:t>ΣΥΝΟΛΟ</w:t></w:r></w:p></w:tc></w:tr>"
        assertEquals(1, DocxTemplate.boldRow(row).countOf("<w:b/>"))
    }

    /**
     * Το συμπτυγμένο πρότυπο γράφει ρητά «όχι έντονο»: `<w:b w:val="0"/>`.
     *
     * Ο έλεγχος «υπάρχει ήδη `<w:b `» το περνούσε για έντονο και γύριζε τη
     * γραμμή ανέγγιχτη. Ο διακόπτης πρέπει να **γυρίσει**, όχι να αγνοηθεί.
     */
    @Test
    fun `το ρητό w b val 0 γυρίζει σε έντονο`() {
        val row = "<w:tr><w:tc><w:p><w:r>" +
            "<w:rPr><w:rFonts w:ascii=\"Arial\"/><w:b w:val=\"0\"/><w:sz w:val=\"17\"/></w:rPr>" +
            "<w:t>ΓΕΝΙΚΟ ΣΥΝΟΛΟ</w:t></w:r></w:p></w:tc></w:tr>"
        val bolded = DocxTemplate.boldRow(row)
        assertEquals(1, bolded.countOf("<w:b/>"))
        assertTrue("το σβηστό <w:b> δεν πρέπει να μείνει", !bolded.contains("w:val=\"0\""))
    }

    /** Το ίδιο, γραμμένο ως `false` — το Word δέχεται και τις δύο μορφές. */
    @Test
    fun `το w b val false γυρίζει κι αυτό`() {
        val row = "<w:tr><w:tc><w:p><w:r><w:rPr><w:b w:val=\"false\"/></w:rPr>" +
            "<w:t>ΣΥΝΟΛΟ</w:t></w:r></w:p></w:tc></w:tr>"
        assertEquals(1, DocxTemplate.boldRow(row).countOf("<w:b/>"))
    }

    /** Ένα ήδη έντονο run δεν αποκτά δεύτερο `<w:b/>`. */
    @Test
    fun `το ήδη έντονο μένει με ένα μόνο w b`() {
        val row = "<w:tr><w:tc><w:p><w:r><w:rPr><w:b w:val=\"1\"/></w:rPr>" +
            "<w:t>ΣΥΝΟΛΟ</w:t></w:r></w:p></w:tc></w:tr>"
        assertEquals(row, DocxTemplate.boldRow(row))
    }

    /**
     * Η πρώτη γραμμή συνόλων του συμπτυγμένου προτύπου φέρει `<<[Αν ΦΠΑ]>>`.
     * Από αυτήν κλωνοποιούνται όλες οι άλλες, οπότε ο δείκτης ταξίδευε παντού
     * και χωρίς ΦΠΑ έσβηνε ολόκληρο το μπλοκ των συνόλων.
     */
    @Test
    fun `ο δείκτης ΦΠΑ δεν κληρονομείται στις άλλες γραμμές`() {
        val built = DocxTemplate.fullTotalsLayout(compactTotalsTable())
        // Έξι γραμμές παράγονται· μόνο η μία είναι του ΦΠΑ.
        assertEquals(
            "ο δείκτης ΦΠΑ ανήκει αποκλειστικά στη γραμμή του ΦΠΑ",
            1,
            built.countOf("&lt;&lt;[Αν ΦΠΑ]&gt;&gt;"),
        )
        assertTrue(built.contains("ΓΕΝΙΚΟ ΣΥΝΟΛΟ"))
    }

    /** Ένας πίνακας με μία μόνο γραμμή συνόλων, όπως στο συμπτυγμένο πρότυπο. */
    private fun compactTotalsTable(): String = "<w:document><w:body><w:tbl><w:tr>" +
        "<w:tc><w:p><w:r><w:rPr><w:b w:val=\"0\"/></w:rPr>" +
        "<w:t>&lt;&lt;[Αν ΦΠΑ]&gt;&gt;ΚΑΘΑΡΗ ΑΞΙΑ</w:t></w:r></w:p></w:tc>" +
        "<w:tc><w:p><w:r><w:rPr><w:b w:val=\"0\"/></w:rPr>" +
        "<w:t>&lt;&lt;[Καθαρή Αξία]&gt;&gt;</w:t></w:r></w:p></w:tc>" +
        "</w:tr></w:tbl></w:body></w:document>"

    private fun String.countOf(needle: String): Int =
        split(needle).size - 1
}
